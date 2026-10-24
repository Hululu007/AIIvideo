"""值班台入口。

路由分五组：登录 / 源 / 清单 / 用户 / 审计。所有写操作过 CSRF，所有失败回模板
（不给原始堆栈），所有动作进审计。

页面要的数据统一从 `registry.inventory()` 来 —— 那是唯一读 CFG_DIR 的地方，
模板不再自己碰盘。
"""
from __future__ import annotations

import datetime
import json
from typing import Optional
from urllib.parse import quote

from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from . import auth, inspect, registry, sources, track, users
from .config import BASE_DIR, CFG_DIR, COOKIE_NAME, DATA_DIR, MOUNT_PATH, PUBLIC_BASE, SESSION_TTL_HOURS
from .db import init_sqlite, sqlite

app = FastAPI(title="AIIvideo 值班台", docs_url=None, redoc_url=None)
app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))
templates.env.globals["mount"] = MOUNT_PATH
# 静态文件按 mtime 打版本号：改了样式不用教用户"强制刷新一下"
ASSET_VERSION = str(int(max(
    (path.stat().st_mtime for path in (BASE_DIR / "static").rglob("*") if path.is_file()),
    default=0,
)))
templates.env.globals["asset"] = ASSET_VERSION
M = MOUNT_PATH


@app.on_event("startup")
def _startup() -> None:
    init_sqlite()
    auth.ensure_admin()
    CFG_DIR.mkdir(parents=True, exist_ok=True)


def inventory(request: Request) -> dict:
    """一次请求只扫一遍盘。"""
    cached = getattr(request.state, "inv", None)
    if cached is None:
        cached = registry.inventory()
        request.state.inv = cached
    return cached


def redirect(to: str) -> RedirectResponse:
    """中文提示词直接拼在 URL 里会被头编码卡住，统一转义。"""
    return RedirectResponse(url=quote(to, safe="/:?=&%#"), status_code=303)


def audit(request: Request, session: dict, action: str, target: str = "", detail: str = "") -> None:
    with sqlite() as conn:
        conn.execute(
            "INSERT INTO audit(actor, action, target, detail, ip) VALUES (?, ?, ?, ?, ?)",
            (session["username"], action, target, detail, auth.client_ip(request)),
        )


def render(request: Request, template: str, session: Optional[dict] = None, **context) -> HTMLResponse:
    inv = getattr(request.state, "inv", None)
    if inv is None and session is not None:
        inv = inventory(request)
    if inv is not None:
        context.setdefault("strip", inv["spider"])
        context.setdefault("inv", inv)
    with sqlite() as conn:
        audit_rows = conn.execute("SELECT count(*) AS n FROM audit").fetchone()["n"]
    context.setdefault("nav", {
        "sources": (len(inv["spider"]) + len(inv["legacy"])) if inv else 0,
        "market": inv["counts"]["entries"] if inv else 0,
        "audit": audit_rows,
        "users": users.total_cached(),
    })
    context.setdefault("csrf", session["csrf"] if session else "")
    context.setdefault("session", session)
    context.setdefault("public_base", PUBLIC_BASE)
    return templates.TemplateResponse(request, template, context)


# ── 登录 ────────────────────────────────────────────────────────────

@app.get("/healthz")
def healthz():
    return {"ok": True}


@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    if auth.current(request):
        return redirect(f"{M}/")
    return render(request, "login.html", error="", today=datetime.date.today().isoformat())


@app.post("/login", response_class=HTMLResponse)
def login_submit(request: Request, username: str = Form(""), password: str = Form("")):
    admin_id = auth.check_credentials(username.strip(), password)
    if not admin_id:
        return render(request, "login.html", error="账号或口令不对",
                      today=datetime.date.today().isoformat())
    token, _csrf = auth.start_session(admin_id)
    response = RedirectResponse(url=f"{M}/", status_code=303)
    response.set_cookie(
        COOKIE_NAME, auth.sign_cookie(token),
        max_age=SESSION_TTL_HOURS * 3600, httponly=True, samesite="lax", path=MOUNT_PATH or "/",
    )
    with sqlite() as conn:
        conn.execute(
            "INSERT INTO audit(actor, action, target, ip) VALUES (?, 'login', '', ?)",
            (username.strip(), auth.client_ip(request)),
        )
    return response


@app.post("/logout")
def logout(request: Request, csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    audit(request, session, "logout")
    auth.end_session(session["token"])
    response = RedirectResponse(url=f"{M}/login", status_code=303)
    response.delete_cookie(COOKIE_NAME, path=MOUNT_PATH or "/")
    return response


# ── 总览 ────────────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
def dashboard(request: Request):
    session = auth.require(request)
    inv = inventory(request)
    try:
        counts = users.stats()
        user_error = ""
    except Exception as exc:
        counts = {"users": "-", "objects": "-", "sessions": "-"}
        user_error = str(exc)
    with sqlite() as conn:
        recent = conn.execute("SELECT * FROM audit ORDER BY id DESC LIMIT 12").fetchall()
    return render(request, "dashboard.html", session, counts=counts, user_error=user_error,
                  recent=recent, cfg_dir=str(CFG_DIR), data_dir=str(DATA_DIR),
                  user_total=counts["users"])


# ── 源 ──────────────────────────────────────────────────────────────

@app.get("/sources", response_class=HTMLResponse)
def sources_page(request: Request, q: str = "", only: str = "", ok: str = "", err: str = ""):
    session = auth.require(request)
    inv = inventory(request)
    needle = q.strip().lower()
    spider = inv["spider"]
    legacy = inv["legacy"]
    if needle:
        spider = [item for item in spider if needle in (item["name"] + item["config"] + item["host"]).lower()]
        legacy = [item for item in legacy if needle in (item["id"] + item["config"]).lower()]
    if only == "todo":
        spider = [item for item in spider if item["lamp"] in {"dead", "warn", "idle"}]
        legacy = [item for item in legacy if item["lamp"] in {"dead", "warn"}]
    return render(request, "sources.html", session, spider=spider, legacy=legacy,
                  q=q, only=only, ok=ok, err=err)


@app.get("/sources/{name:path}/edit", response_class=HTMLResponse)
def source_edit(request: Request, name: str, ok: str = "", err: str = ""):
    session = auth.require(request)
    inventory(request)
    try:
        rel = sources.safe_rel(name)
        text = sources.read_text(rel)
    except sources.SourceError as exc:
        return redirect(f"{M}/sources?err={exc}")
    info = inspect.describe(rel, text)
    return render(request, "edit.html", session, name=rel, text=text, info=info,
                  backups=sources.backups(rel), ok=ok, err=err,
                  is_json=rel.endswith(".json"), public=registry.public_url(rel))


@app.post("/sources/{name:path}/save")
def source_save(request: Request, name: str, text: str = Form(""), csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    try:
        result = sources.save_text(name, text)
    except sources.SourceError as exc:
        return redirect(f"{M}/sources/{name}/edit?err={exc}")
    audit(request, session, "source.save", result["name"], f"{result['bytes']} 字节")
    return redirect(f"{M}/sources/{name}/edit?ok=已保存，App 下次刷新就吃到这一版")


@app.post("/sources/{name:path}/restore")
def source_restore(request: Request, name: str, stamp: str = Form(""), csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    try:
        result = sources.restore(name, stamp)
    except sources.SourceError as exc:
        return redirect(f"{M}/sources/{name}/edit?err={exc}")
    audit(request, session, "source.restore", result["name"], f"from {result['restored_from']}")
    return redirect(f"{M}/sources/{name}/edit?ok=已回滚到 {stamp} 那一版")


@app.post("/sources/{name:path}/delete")
def source_delete(request: Request, name: str, csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    try:
        rel = sources.safe_rel(name)
        sources.delete(rel)
    except sources.SourceError as exc:
        return redirect(f"{M}/sources?err={exc}")
    audit(request, session, "source.delete", rel)
    return redirect(f"{M}/sources?ok=已删除 {rel}（删之前留了一份备份）")


@app.post("/sources/new")
def source_new(request: Request, name: str = Form(""), kind: str = Form("spider"), csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    stem = name.strip()
    if stem and not stem.endswith(".json"):
        stem += ".json"
    rel = f"spiders/{stem}" if kind == "spider" and stem else stem
    template = {
        "_note": "新建的源。web.spider 指向 /spiders/<id>.js，js 要另外传上去。",
        "web": {"spider": f"{PUBLIC_BASE}/spiders/{stem.replace('.json', '.js')}", "firstPageSize": 20, "pageSize": 20},
        "cover": {"mode": "plain", "allowedHosts": []},
    } if kind == "spider" else {
        "adapter": "cms",
        "api": "https://example.com/api.php/provide/vod/",
        "cover": {"mode": "plain", "allowedHosts": []},
    }
    try:
        sources.create(rel, json.dumps(template, ensure_ascii=False, indent=2))
    except sources.SourceError as exc:
        return redirect(f"{M}/sources?err={exc}")
    audit(request, session, "source.create", rel)
    return redirect(f"{M}/sources/{rel}/edit?ok=已新建，接着改内容")


# ── 清单 ────────────────────────────────────────────────────────────

@app.get("/market", response_class=HTMLResponse)
def market_page(request: Request, ok: str = "", err: str = ""):
    session = auth.require(request)
    inv = inventory(request)
    # 已发布的按清单里的原顺序排，没发布的挂后面 —— 保存时按这个顺序重建，别打乱用户看到的次序
    order = {entry["target"]: index for index, entry in enumerate(inv["entries"])}
    spider = sorted(
        inv["spider"],
        key=lambda item: (order.get(item["config"].rsplit("/", 1)[-1], 999), item["name"]),
    )
    try:
        legacy = json.loads(sources.read_text("market.json")).get("sources", [])
    except Exception:
        legacy = []
    return render(request, "market.html", session, spider=spider, entries=inv["entries"], legacy=legacy,
                  market_error=inv["market_error"], ok=ok, err=err)


@app.post("/market/save")
def market_save(request: Request, csrf: str = Form(""),
                row: list[str] = Form(default=[]), pick: list[str] = Form(default=[]),
                name: list[str] = Form(default=[]), desc: list[str] = Form(default=[])):
    """按勾选重建 market-spider.json。

    `row` 每行一个源（顺序就是渲染顺序），`name`/`desc` 与它按下标对齐，
    `pick` 是勾上的那几行 —— 未勾的 checkbox 不提交，所以不能拿它对齐。
    """
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    picked = set(pick)
    items = []
    for index, rel in enumerate(row):
        if rel not in picked:
            continue
        items.append({
            "name": (name[index] if index < len(name) else "").strip() or rel.rsplit("/", 1)[-1].replace(".json", ""),
            "desc": (desc[index] if index < len(desc) else "").strip(),
            "url": registry.public_url(rel),
        })
    if not items:
        return redirect(f"{M}/market?err=一条都没勾，清单不能是空的")
    # 原来那份 _note 是给人看的长说明，改写清单时别丢
    note = ("spider 源清单。和 market.json 是两份东西：那份是老用户用的 REST 配置源，"
            "这份全是 spider 源 —— 内容由 App 内置的 spider 运行时跑 js 提供。"
            "装法：设置页贴本文件地址，勾选后逐条装。")
    try:
        note = json.loads(sources.read_text("market-spider.json")).get("_note") or note
    except Exception:
        pass
    payload = {
        "updated": datetime.date.today().isoformat(),
        "_note": note,
        "sources": items,
    }
    try:
        sources.save_text("market-spider.json", json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    except sources.SourceError as exc:
        return redirect(f"{M}/market?err={exc}")
    audit(request, session, "market.save", "market-spider.json", f"{len(items)} 条")
    return redirect(f"{M}/market?ok=已写入 {len(items)} 条")


# ── 用户 ────────────────────────────────────────────────────────────

@app.get("/users", response_class=HTMLResponse)
def users_page(request: Request, q: str = "", page: int = 1, ok: str = "", err: str = ""):
    session = auth.require(request)
    try:
        listing = users.list_users(q, page)
        user_error = ""
    except Exception as exc:
        listing = {"users": [], "total": 0, "page": 1, "has_more": False, "query": q}
        user_error = str(exc)
    return render(request, "users.html", session, listing=listing, user_error=user_error,
                  ok=ok, err=err, user_total=listing["total"])


@app.post("/users/{user_id}/password")
def user_password(request: Request, user_id: str, password: str = Form(""), csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    try:
        users.reset_password(user_id, password)
    except Exception as exc:
        return redirect(f"{M}/users?err={exc}")
    audit(request, session, "user.password", user_id)
    return redirect(f"{M}/users?ok=口令已重置，这个用户的旧会话全踢了")


@app.post("/users/{user_id}/delete")
def user_delete(request: Request, user_id: str, csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    try:
        name = users.delete_user(user_id)
    except Exception as exc:
        return redirect(f"{M}/users?err={exc}")
    audit(request, session, "user.delete", user_id, name)
    return redirect(f"{M}/users?ok=已删除用户 {name}")


# ── 审计 ────────────────────────────────────────────────────────────

@app.get("/audit", response_class=HTMLResponse)
def audit_page(request: Request):
    session = auth.require(request)
    with sqlite() as conn:
        rows = conn.execute("SELECT * FROM audit ORDER BY id DESC LIMIT 300").fetchall()
    return render(request, "audit.html", session, rows=rows)


# ── 数据（埋点） ────────────────────────────────────────────────────
#
# 这里的数不是后台自己算的：埋点库一步都没出 track 的 compose 网络，后台够不着。
# 所以整页靠 track 服务的 /stats/* 两个只读接口，失败也要能显示 —— 概览挂了
# 事件流可能还在，两条路各报各的错。

@app.get("/data", response_class=HTMLResponse)
def data_page(request: Request, d: int = 7, kind: str = "", clean: int = 0):
    session = auth.require(request)
    inventory(request)          # base.html 的灯带要它
    days = d if d in (1, 7, 30, 90) else 7
    overview, data_error = track.overview(days)
    stream, stream_error = track.events(60, kind, bool(clean))
    return render(request, "data.html", session,
                  ov=overview, data_error=data_error,
                  stream=stream, stream_error=stream_error,
                  d=days, kind=kind, clean=bool(clean))


# ── 账号 ────────────────────────────────────────────────────────────

@app.get("/account", response_class=HTMLResponse)
def account_page(request: Request, ok: str = "", err: str = ""):
    session = auth.require(request)
    return render(request, "account.html", session, ok=ok, err=err)


@app.post("/account/password")
def account_password(request: Request, old: str = Form(""), new: str = Form(""),
                      repeat: str = Form(""), csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    if not auth.check_credentials(session["username"], old):
        return redirect(f"{M}/account?err=原口令不对")
    if len(new) < 8:
        return redirect(f"{M}/account?err=新口令至少 8 位")
    if new != repeat:
        return redirect(f"{M}/account?err=两次输入不一致")
    auth.change_password(session["admin_id"], new)
    audit(request, session, "admin.password", session["username"])
    return redirect(f"{M}/account?ok=口令已更新")
