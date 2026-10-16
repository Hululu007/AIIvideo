"""
AIIvideo 账号后端 —— Parse REST 的一个子集，数据落在 PostgreSQL。

── 为什么做成 Parse 兼容 ────────────────────────────────────────────────
这个 APK 里打包了完整的 Parse Android SDK（115 个类）。原版把账号、收藏、
观看历史全部挂在 Back4App（一家托管 Parse 服务）上，客户端代码是这么写的：

    ParseUser.logIn(...) / ParseUser.signUp(...)
    ParseObject("UserLibrary") / ParseQuery.whereEqualTo("owner", userId)
    ParseFile（头像）

所以最省事、也最不容易出错的路子是**沿用这套协议，只把服务器地址换掉**：

    HuangGuoApp.smali 里那个 "https://parseapi.back4app.com/"  →  我们自己的地址

客户端一行逻辑都不用改，登录、注册、收藏、历史、同步、头像、改密码全部照跑。
反过来自己重写客户端（拆掉 Parse 换成裸 REST）要动 2000 多行反编译出来的 smali，
那是在给自己找麻烦。

── 实现了哪些 ────────────────────────────────────────────────────────────
    POST   /users              注册
    GET    /login              登录（query）
    POST   /login              登录（body）
    POST   /logout             退出
    GET    /users/me           当前用户
    GET    /users/<id>         按 id 取
    PUT    /users/<id>         改用户名 / 密码 / 头像
    GET    /users              查用户
    POST   /classes/<cls>      建对象
    GET    /classes/<cls>      查对象（where / limit / order / keys）
    GET    /classes/<cls>/<id>
    PUT    /classes/<cls>/<id>
    DELETE /classes/<cls>/<id>
    POST   /files/<name>       上传（头像）
    GET    /files/<appid>/<name>[:/...]  取回

Parse 那套「REST API Key / Master Key」一概不要：applicationId 对不上就 403，
其余全看 sessionToken。

── 认证 ──────────────────────────────────────────────────────────────────
口令用 scrypt（hashlib，标准库，不引第三方）。参数写在 PASSWORD_N/R/P 里，
改这三个值会让所有旧口令失效 —— 不许改。

── 时间格式 ──────────────────────────────────────────────────────────────
Parse 用 `2026-10-16T03:00:00.000Z`（毫秒 + Z）。**不能少毫秒** ——
SDK 里那个日期解析器认死这个形状，少一位就是解析异常。
"""

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import string
import time
from datetime import datetime, timezone
from typing import Any, Optional

import psycopg
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, Response
from psycopg.rows import dict_row

# ── 配置 ──────────────────────────────────────────────────────────────────

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql://aii:aii@db:5432/aii"
)
# 客户端要发的两个头。值必须和 APK 里 res/values/strings.xml 的那两条一致，
# 否则 Parse SDK 收到的 403 会被它翻译成一个很含糊的错误，不好查。
APP_ID = os.environ.get("PARSE_APP_ID", "")
CLIENT_KEY = os.environ.get("PARSE_CLIENT_KEY", "")
# 检查 clientKey 的开关。SDK 在某些请求上不带这个头，所以默认只查 appId。
STRICT_CLIENT_KEY = os.environ.get("STRICT_CLIENT_KEY", "0") == "1"

PASSWORD_N = 2 ** 14
PASSWORD_R = 8
PASSWORD_P = 1
PASSWORD_LEN = 32

# Parse 的对象 id 是 10 位字母数字。客户端会把它当字符串到处塞（拼 SharedPreferences
# 的 key、拼 URL），所以格式跟着它走，别换成 UUID。
ID_ALPHABET = string.ascii_letters + string.digits
ID_LENGTH = 10

SESSION_DAYS = 365

APP = FastAPI(title="AIIvideo account", docs_url=None, redoc_url=None)


# ── 小工具 ────────────────────────────────────────────────────────────────


def new_id() -> str:
    return "".join(secrets.choice(ID_ALPHABET) for _ in range(ID_LENGTH))


def now_iso() -> str:
    """Parse 的时间格式：毫秒 + Z。少毫秒 SDK 会解析失败。"""
    dt = datetime.now(timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def to_iso(value: Any) -> str:
    """库里取出来的时间 → Parse 格式。"""
    if isinstance(value, datetime):
        dt = value.astimezone(timezone.utc)
        return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"
    return now_iso()


def hash_password(password: str, salt: Optional[bytes] = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode("utf-8"), salt=salt, n=PASSWORD_N, r=PASSWORD_R,
        p=PASSWORD_P, dklen=PASSWORD_LEN,
    )
    return "scrypt$%d$%d$%d$%s$%s" % (
        PASSWORD_N, PASSWORD_R, PASSWORD_P,
        base64.b64encode(salt).decode(), base64.b64encode(digest).decode(),
    )


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, n, r, p, salt_b64, digest_b64 = stored.split("$")
        if scheme != "scrypt":
            return False
        digest = hashlib.scrypt(
            password.encode("utf-8"), salt=base64.b64decode(salt_b64),
            n=int(n), r=int(r), p=int(p), dklen=len(base64.b64decode(digest_b64)),
        )
        return hmac.compare_digest(digest, base64.b64decode(digest_b64))
    except Exception:
        return False


def parse_error(code: int, message: str, status: int = 400) -> JSONResponse:
    """
    Parse 的错误格式。**code 必须用官方那套数字** —— 客户端是按 code 分支的，
    比如 AccountService.login 里 `getCode()==101` 才报「账号不存在或密码错误」，
    换成自定义数字，用户看到的就是一句没有上下文的英文。
    """
    return JSONResponse({"code": code, "error": message}, status_code=status)


PARSE_BAD_REQUEST = 107
PARSE_INVALID_CREDENTIALS = 101
PARSE_INVALID_SESSION = 209
PARSE_USERNAME_TAKEN = 202
PARSE_OBJECT_NOT_FOUND = 101
PARSE_MISSING_USER = 205
PARSE_SESSION_MISSING = 206


def connect():
    return psycopg.connect(DATABASE_URL, row_factory=dict_row, autocommit=False)


def check_app(app_id: Optional[str], client_key: Optional[str]) -> Optional[JSONResponse]:
    if APP_ID and app_id != APP_ID:
        return parse_error(403, "application id 不对", 403)
    if STRICT_CLIENT_KEY and CLIENT_KEY and client_key != CLIENT_KEY:
        return parse_error(403, "client key 不对", 403)
    return None


def user_json(row: dict) -> dict:
    out = {
        "objectId": row["id"],
        "username": row["username"],
        "createdAt": to_iso(row["created_at"]),
        "updatedAt": to_iso(row["updated_at"]),
    }
    if row.get("email"):
        out["email"] = row["email"]
    if row.get("avatar_url"):
        out["avatar"] = {"__type": "File", "name": row["avatar_url"], "url": file_url(row["avatar_url"])}
    return out


# 对外可见的地址前缀。Caddy 用 handle_path /api/* 把前缀剥掉再转给 API，
# 所以进程自己**不知道**外面还挂着一层 /api —— 头像的 URL 只能靠这个变量拼。
# 从 request 里推是不行的：剥掉前缀之后 base_url 就不含它了，拼出来是
# https://host/files/xxx，而真实地址是 https://host/api/files/xxx。
PUBLIC_BASE = os.environ.get("PUBLIC_BASE", "").rstrip("/")


def file_url(name: str) -> str:
    path = f"/files/{APP_ID or 'app'}/{name}"
    return (PUBLIC_BASE + path) if PUBLIC_BASE else path


def object_json(row: dict) -> dict:
    data = dict(row["data"] or {})
    data["objectId"] = row["id"]
    data["createdAt"] = to_iso(row["created_at"])
    data["updatedAt"] = to_iso(row["updated_at"])
    if row.get("acl"):
        data["ACL"] = row["acl"]
    return data


# ── 启动建表 ──────────────────────────────────────────────────────────────


# 建表用的咨询锁编号。随便挑一个固定值，只要这个库里没人用同一个号就行。
SCHEMA_LOCK = 0x4149_4956  # "AIIV"


@APP.on_event("startup")
def on_startup() -> None:
    """
    建表。

    ⚠️ **必须加锁。** uvicorn 起了 2 个 worker，它们会同时跑这段 ——
    而 Postgres 的 `CREATE TABLE IF NOT EXISTS` **不是原子的**：两个会话可以
    同时通过「不存在」的检查，然后一个成功、另一个撞
    `duplicate key value violates unique constraint "pg_type_typname_nsp_index"`，
    那个 worker 直接启动失败退出。加锁之后它们排队进，先到的建完、后到的看到已存在。

    （第一次部署就是这么挂的：容器报 healthy（健康检查探的是另一个活着的 worker），
      日志里却是 `Application startup failed`。）
    """
    schema = os.path.join(os.path.dirname(__file__), "schema.sql")
    with open(schema, "r", encoding="utf-8") as handle:
        sql = handle.read()
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute("select pg_advisory_lock(%s)", (SCHEMA_LOCK,))
            try:
                cur.execute(sql)
                conn.commit()
            finally:
                cur.execute("select pg_advisory_unlock(%s)", (SCHEMA_LOCK,))
    print(f"[account] 建表完成 appId={'有' if APP_ID else '无'} strictClientKey={STRICT_CLIENT_KEY}")


@APP.get("/healthz")
def healthz():
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute("select count(*) as n from users")
            n = cur.fetchone()["n"]
    return {"ok": True, "users": n}


# ── session ───────────────────────────────────────────────────────────────


def session_user(conn, token: Optional[str]) -> dict:
    if not token:
        raise HTTPException(status_code=401, detail=json.dumps({"code": PARSE_SESSION_MISSING, "error": "没有 session token"}))
    with conn.cursor() as cur:
        cur.execute(
            "select u.* from sessions s join users u on u.id = s.user_id "
            "where s.token = %s and (s.expires_at is null or s.expires_at > now())",
            (token,),
        )
        row = cur.fetchone()
    if not row:
        raise HTTPException(status_code=401, detail=json.dumps({"code": PARSE_INVALID_SESSION, "error": "session 无效或已过期"}))
    return row


def issue_session(conn, user_id: str) -> str:
    token = "r:" + secrets.token_urlsafe(32)
    with conn.cursor() as cur:
        cur.execute(
            "insert into sessions(token, user_id, expires_at) "
            "values (%s, %s, now() + interval '%s days')",
            (token, user_id, SESSION_DAYS),
        )
    return token


@APP.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    """FastAPI 默认把 detail 包成 {"detail": ...}，Parse 客户端读的是顶层 code/error。"""
    detail = exc.detail
    if isinstance(detail, str) and detail.startswith("{"):
        try:
            return JSONResponse(json.loads(detail), status_code=exc.status_code)
        except Exception:
            pass
    return JSONResponse({"code": PARSE_BAD_REQUEST, "error": str(detail)}, status_code=exc.status_code)


# ── 用户 ──────────────────────────────────────────────────────────────────

USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{3,24}$")


def create_user(conn, username: str, password: str) -> dict:
    if not USERNAME_RE.match(username or ""):
        raise HTTPException(status_code=400, detail=json.dumps(
            {"code": PARSE_BAD_REQUEST, "error": "账号需为 3-24 位字母、数字或下划线"}))
    if not password or len(password) < 8:
        raise HTTPException(status_code=400, detail=json.dumps(
            {"code": PARSE_BAD_REQUEST, "error": "密码至少需要 8 位"}))

    uid = new_id()
    with conn.cursor() as cur:
        cur.execute("select 1 from users where lower(username) = lower(%s)", (username,))
        if cur.fetchone():
            raise HTTPException(status_code=400, detail=json.dumps(
                {"code": PARSE_USERNAME_TAKEN, "error": "这个账号已经被注册了"}))
        cur.execute(
            "insert into users(id, username, password_hash) values (%s, %s, %s)",
            (uid, username, hash_password(password)),
        )
        cur.execute("select * from users where id = %s", (uid,))
        return cur.fetchone()


@APP.post("/users")
async def signup(request: Request, x_parse_application_id: Optional[str] = Header(None),
                 x_parse_client_key: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, x_parse_client_key)
    if bad:
        return bad
    body = await request.json()
    username = (body.get("username") or "").strip()
    password = body.get("password") or ""
    with connect() as conn:
        row = create_user(conn, username, password)
        token = issue_session(conn, row["id"])
        conn.commit()
    payload = user_json(row)
    payload["sessionToken"] = token
    return payload


@APP.post("/login")
async def login_post(request: Request, x_parse_application_id: Optional[str] = Header(None),
                     x_parse_client_key: Optional[str] = Header(None)):
    body = await request.json()
    return await _login(body.get("username", ""), body.get("password", ""),
                        x_parse_application_id, x_parse_client_key)


@APP.get("/login")
async def login_get(username: str = "", password: str = "",
                    x_parse_application_id: Optional[str] = Header(None),
                    x_parse_client_key: Optional[str] = Header(None)):
    return await _login(username, password, x_parse_application_id, x_parse_client_key)


async def _login(username: str, password: str, app_id: Optional[str], client_key: Optional[str]):
    bad = check_app(app_id, client_key)
    if bad:
        return bad
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute("select * from users where lower(username) = lower(%s)", ((username or "").strip(),))
            row = cur.fetchone()
        # 账号不存在和密码错误返回**同一个** code。分开报的话，输入框就成了
        # 「这个用户名有没有人注册」的探测接口。
        if not row or not verify_password(password or "", row["password_hash"]):
            return parse_error(PARSE_INVALID_CREDENTIALS, "账号不存在或密码错误", 404)
        token = issue_session(conn, row["id"])
        conn.commit()
    payload = user_json(row)
    payload["sessionToken"] = token
    return payload


@APP.post("/logout")
async def logout(x_parse_session_token: Optional[str] = Header(None),
                 x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    if x_parse_session_token:
        with connect() as conn:
            with conn.cursor() as cur:
                cur.execute("delete from sessions where token = %s", (x_parse_session_token,))
            conn.commit()
    return {}


@APP.get("/users/me")
async def users_me(x_parse_session_token: Optional[str] = Header(None),
                   x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    with connect() as conn:
        row = session_user(conn, x_parse_session_token)
    return user_json(row)


@APP.get("/users")
async def users_query(where: Optional[str] = None, limit: int = 100,
                      x_parse_session_token: Optional[str] = Header(None),
                      x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    cond = json.loads(where) if where else {}
    with connect() as conn:
        session_user(conn, x_parse_session_token)
        with conn.cursor() as cur:
            cur.execute("select * from users where username = %s limit %s",
                        (cond.get("username"), min(limit, 100)))
            rows = cur.fetchall()
    return {"results": [user_json(r) for r in rows]}


@APP.get("/users/{user_id}")
async def users_get(user_id: str, x_parse_session_token: Optional[str] = Header(None),
                    x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    with connect() as conn:
        session_user(conn, x_parse_session_token)
        with conn.cursor() as cur:
            cur.execute("select * from users where id = %s", (user_id,))
            row = cur.fetchone()
    if not row:
        return parse_error(PARSE_OBJECT_NOT_FOUND, "用户不存在", 404)
    return user_json(row)


@APP.put("/users/{user_id}")
async def users_update(user_id: str, request: Request,
                       x_parse_session_token: Optional[str] = Header(None),
                       x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    body = await request.json()
    with connect() as conn:
        me = session_user(conn, x_parse_session_token)
        if me["id"] != user_id:
            return parse_error(PARSE_INVALID_SESSION, "只能改自己的资料", 403)
        sets, values = [], []
        if "username" in body:
            name = (body["username"] or "").strip()
            if not USERNAME_RE.match(name):
                return parse_error(PARSE_BAD_REQUEST, "账号需为 3-24 位字母、数字或下划线")
            with conn.cursor() as cur:
                cur.execute("select 1 from users where lower(username) = lower(%s) and id <> %s", (name, user_id))
                if cur.fetchone():
                    return parse_error(PARSE_USERNAME_TAKEN, "这个账号已经被注册了")
            sets.append("username = %s")
            values.append(name)
        if "password" in body:
            new_password = body["password"] or ""
            if len(new_password) < 8:
                return parse_error(PARSE_BAD_REQUEST, "密码至少需要 8 位")
            sets.append("password_hash = %s")
            values.append(hash_password(new_password))
        if "email" in body:
            sets.append("email = %s")
            values.append(body["email"])
        if isinstance(body.get("avatar"), dict):
            sets.append("avatar_url = %s")
            values.append(body["avatar"].get("name"))
        if not sets:
            return {"updatedAt": now_iso()}
        sets.append("updated_at = now()")
        values.append(user_id)
        with conn.cursor() as cur:
            cur.execute(f"update users set {', '.join(sets)} where id = %s", values)
            cur.execute("select updated_at from users where id = %s", (user_id,))
            row = cur.fetchone()
        conn.commit()
    return {"updatedAt": to_iso(row["updated_at"])}


# ── 对象（收藏 / 观看历史都在这儿）────────────────────────────────────────


def scalar_of(value: Any) -> Any:
    """
    Parse 的带类型包装 → 能直接比的值。

    实测客户端发过来的 where 长这样：

        {"owner": {"__type": "Pointer", "className": "_User", "objectId": "q37..."}}

    不是字符串。直接 str() 会得到一整串 JSON，跟什么都对不上，查询永远返回空 ——
    而界面上看起来就是「收藏和历史都没了」，不报错。所以这里必须拆。
    """
    if isinstance(value, dict):
        kind = value.get("__type")
        if kind == "Pointer":
            return value.get("objectId")
        if kind == "Date":
            return value.get("iso")
        if kind == "File":
            return value.get("name")
        return json.dumps(value, sort_keys=True, ensure_ascii=False)
    return value


def safe_field(name: str) -> str:
    """
    字段名要拼进 SQL（`data->>'xxx'`），所以只放行字母数字下划线。
    客户端的字段名本来就是自己写的，但 where / order 是**请求参数**，
    值能参数化、标识符不能 —— 这里有一步过滤，才敢往 SQL 里拼。
    """
    return re.sub(r"[^A-Za-z0-9_]", "", name or "")


def where_to_sql(where: Optional[str]):
    """
    把 Parse 的 where 翻成 SQL。只认等值和 $in —— 客户端只用了这两种。

    `owner` 单独处理：它既是对象上的一个字段（客户端 put 进去的），
    也对应表上的 owner_id 列（从 ACL 解出来的）。两个都查，
    免得将来客户端改了写法就对不上。
    """
    if not where:
        return "", []
    cond = json.loads(where)
    clauses, values = [], []
    for key, value in cond.items():
        if key == "owner":
            clauses.append("owner_id = %s")
            values.append(str(scalar_of(value)))
            continue
        field = safe_field(key)
        if not field:
            continue
        if isinstance(value, dict) and "$in" in value:
            clauses.append(f"data->>'{field}' = any(%s::text[])")
            values.append([str(scalar_of(v)) for v in value["$in"]])
            continue
        if isinstance(value, dict) and "$ne" in value:
            clauses.append(f"(data->>'{field}' is distinct from %s)")
            values.append(str(scalar_of(value["$ne"])))
            continue
        clauses.append(f"data->>'{field}' = %s")
        values.append(str(scalar_of(value)))
    return (" and ".join(clauses) if clauses else "true"), values


def order_to_sql(order: Optional[str]) -> str:
    """排序字段同样过滤 —— 它是从 query string 直接来的。"""
    if not order:
        return "updated_at desc"
    parts = []
    for raw in (order or "").split(","):
        raw = raw.strip()
        if not raw:
            continue
        desc = raw.startswith("-")
        field = raw[1:] if desc else raw
        if field in ("createdAt", "updatedAt"):
            column = "created_at" if field == "createdAt" else "updated_at"
            parts.append(f"{column} {'desc' if desc else 'asc'}")
        else:
            safe = safe_field(field)
            if safe:
                parts.append(f"data->>'{safe}' {'desc' if desc else 'asc'}")
    return ", ".join(parts) if parts else "updated_at desc"


@APP.post("/classes/{cls}")
async def class_create(cls: str, request: Request,
                       x_parse_session_token: Optional[str] = Header(None),
                       x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    body = await request.json()

    # Parse SDK 对「where 太长」的查询**不是发 GET**，而是 POST 到同一个地址、
    # 用 body 里的 `_method` 标真实动作。实测发过来的就是：
    #   POST /classes/UserLibrary  {"where":{…},"limit":1,"order":"-updatedAt","_method":"GET"}
    # 不认这个的话，那次查询会被当成「建一个新对象」写进去 —— 库里就多一条
    # 内容是查询参数的垃圾记录，而客户端的收藏列表是空的（它等的是 results）。
    method = str(body.pop("_method", "") or "").upper()
    if method == "GET":
        return await _class_query(cls, query_params(body), x_parse_session_token,
                                  x_parse_application_id)

    acl = body.pop("ACL", None)
    owner = None
    if isinstance(acl, dict):
        for key in acl:
            if key != "*":
                owner = key
    oid = new_id()
    with connect() as conn:
        me = session_user(conn, x_parse_session_token)
        # ACL 里没写主人就用当前登录用户兜底。**不能留 null** ——
        # 查询是按 owner_id 过滤的，null 的对象等于谁都查不到（或者更糟，谁都查得到）。
        if not owner:
            owner = me["id"]
        with conn.cursor() as cur:
            cur.execute(
                "insert into objects(id, class_name, owner_id, data, acl) values (%s, %s, %s, %s, %s)",
                (oid, cls, owner, json.dumps(body), json.dumps(acl) if acl else None),
            )
            cur.execute("select created_at from objects where id = %s", (oid,))
            row = cur.fetchone()
        conn.commit()
    return {"objectId": oid, "createdAt": to_iso(row["created_at"])}


def query_params(source: dict) -> dict:
    """Parse 的查询参数。GET 时来自 query string，POST 时来自 body。"""
    def as_int(key: str, fallback: int) -> int:
        try:
            return int(source.get(key))
        except (TypeError, ValueError):
            return fallback
    where = source.get("where")
    if isinstance(where, dict):                 # POST 时 where 可能已经是对象
        where = json.dumps(where)
    return {
        "where": where,
        "limit": as_int("limit", 100),
        "skip": as_int("skip", 0),
        "order": source.get("order"),
        "keys": source.get("keys"),
    }


@APP.get("/classes/{cls}")
async def class_query(cls: str, where: Optional[str] = None, limit: int = 100,
                      skip: int = 0, order: Optional[str] = None, keys: Optional[str] = None,
                      x_parse_session_token: Optional[str] = Header(None),
                      x_parse_application_id: Optional[str] = Header(None)):
    return await _class_query(cls, {"where": where, "limit": limit, "skip": skip,
                                    "order": order, "keys": keys},
                              x_parse_session_token, x_parse_application_id)


async def _class_query(cls: str, params: dict, token: Optional[str], app_id: Optional[str]):
    bad = check_app(app_id, None)
    if bad:
        return bad
    where = params.get("where")
    limit = params.get("limit", 100)
    skip = params.get("skip", 0)
    order = params.get("order")
    clause, values = where_to_sql(where)
    order_sql = order_to_sql(order)

    with connect() as conn:
        me = session_user(conn, token)
        # **只返回自己的对象，而且不接受例外。**
        # 客户端本来就带 `where owner=<自己>`，但那是客户端在做过滤 ——
        # 服务端不自己再拦一道的话，改个请求就能把别人的收藏全拉走。
        # （早先这里写的是 `owner_id = me or owner_id is null`，本意是兼容
        #  没设 ACL 的旧数据，实际后果是「凡是没有归属的对象对所有人可见」。
        #  现在创建时一定会写 owner_id，null 不再有合法来源。）
        sql = (
            "select * from objects where class_name = %s and owner_id = %s "
            f"and ({clause}) order by {order_sql} limit %s offset %s"
        )
        with conn.cursor() as cur:
            cur.execute(sql, [cls, me["id"]] + values + [min(limit, 1000), max(skip, 0)])
            rows = cur.fetchall()
    return {"results": [object_json(r) for r in rows]}


@APP.get("/classes/{cls}/{oid}")
async def class_get(cls: str, oid: str,
                    x_parse_session_token: Optional[str] = Header(None),
                    x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    with connect() as conn:
        me = session_user(conn, x_parse_session_token)
        with conn.cursor() as cur:
            cur.execute("select * from objects where class_name = %s and id = %s", (cls, oid))
            row = cur.fetchone()
    if not row or row["owner_id"] != me["id"]:
        return parse_error(PARSE_OBJECT_NOT_FOUND, "对象不存在", 404)
    return object_json(row)


@APP.put("/classes/{cls}/{oid}")
async def class_update(cls: str, oid: str, request: Request,
                       x_parse_session_token: Optional[str] = Header(None),
                       x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    body = await request.json()
    # 同上：POST + _method=PUT 也是合法的发法
    if str(body.pop("_method", "") or "").upper() == "DELETE":
        return await class_delete(cls, oid, x_parse_session_token, x_parse_application_id)
    with connect() as conn:
        me = session_user(conn, x_parse_session_token)
        with conn.cursor() as cur:
            cur.execute("select * from objects where class_name = %s and id = %s", (cls, oid))
            row = cur.fetchone()
            if not row or row["owner_id"] != me["id"]:
                return parse_error(PARSE_OBJECT_NOT_FOUND, "对象不存在", 404)
            merged = dict(row["data"] or {})
            merged.update(body)
            cur.execute(
                "update objects set data = %s, updated_at = now() where id = %s returning updated_at",
                (json.dumps(merged), oid),
            )
            stamp = cur.fetchone()["updated_at"]
        conn.commit()
    return {"updatedAt": to_iso(stamp)}


@APP.delete("/classes/{cls}/{oid}")
async def class_delete(cls: str, oid: str,
                       x_parse_session_token: Optional[str] = Header(None),
                       x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    with connect() as conn:
        me = session_user(conn, x_parse_session_token)
        with conn.cursor() as cur:
            cur.execute(
                "delete from objects where class_name = %s and id = %s and owner_id = %s",
                (cls, oid, me["id"]),
            )
        conn.commit()
    return {}


# ── 文件（头像）─────────────────────────────────────────────────────────


@APP.post("/files/{name}")
async def file_upload(name: str, request: Request,
                      x_parse_session_token: Optional[str] = Header(None),
                      x_parse_application_id: Optional[str] = Header(None)):
    bad = check_app(x_parse_application_id, None)
    if bad:
        return bad
    body = await request.body()
    content_type = request.headers.get("content-type", "application/octet-stream")
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
    stored = f"{new_id()}_{safe}"
    with connect() as conn:
        session_user(conn, x_parse_session_token)
        with conn.cursor() as cur:
            cur.execute(
                "insert into files(name, content, content_type) values (%s, %s, %s) "
                "on conflict (name) do update set content = excluded.content",
                (stored, body, content_type),
            )
        conn.commit()
    return {"name": stored, "url": file_url(stored)}


@APP.get("/files/{app_id}/{name:path}")
async def file_get(app_id: str, name: str):
    # 文件不查 session：Parse 的图片 URL 是直接给 <img> 用的，浏览器不会带自定义头。
    # 文件名里有 10 位随机串，等于是不可猜的地址。
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute("select content, content_type from files where name = %s", (name,))
            row = cur.fetchone()
    if not row:
        return parse_error(PARSE_OBJECT_NOT_FOUND, "文件不存在", 404)
    return Response(content=bytes(row["content"]),
                    media_type=row["content_type"] or "application/octet-stream",
                    headers={"Cache-Control": "public, max-age=31536000"})
