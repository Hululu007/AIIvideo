"""运维后台入口。

路由分四组：登录 / 源文件 / 市场清单 / 用户。所有写操作都过 CSRF，
所有失败都回模板渲染（不给原始堆栈），所有动作都进审计。
"""
from __future__ import annotations

import datetime
import json
from typing import Optional

from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from . import auth, sources, users
from .config import BASE_DIR, CFG_DIR, COOKIE_NAME, MOUNT_PATH, PUBLIC_BASE, SESSION_TTL_HOURS
from .db import init_sqlite, sqlite

app = FastAPI(title="AIIvideo admin", docs_url=None, redoc_url=None)
app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))
templates.env.globals["mount"] = MOUNT_PATH
M = MOUNT_PATH


@app.on_event("startup")
def _startup() -> None:
    init_sqlite()
    auth.ensure_admin()
    CFG_DIR.mkdir(parents=True, exist_ok=True)


def audit(request: Request, session: dict, action: str, target: str = "", detail: str = "") -> None:
    with sqlite() as conn:
        conn.execute(
            "INSERT INTO audit(actor, action, target, detail, ip) VALUES (?, ?, ?, ?, ?)",
            (session["username"], action, target, detail, auth.client_ip(request)),
        )


def render(request: Request, template: str, session: Optional[dict] = None, **context) -> HTMLResponse:
    context.setdefault("csrf", session["csrf"] if session else "")
    context.setdefault("session", session)
    context.setdefault("public_base", PUBLIC_BASE)
    return templates.TemplateResponse(request, template, context)


def redirect(to: str) -> RedirectResponse:
    response = RedirectResponse(url=to, status_code=303)
    return response


# ── 登录 ────────────────────────────────────────────────────────────

@app.get("/healthz")
def healthz():
    return {"ok": True}


@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    if auth.current(request):
        return redirect(f"{M}/")
    return render(request, "login.html", error="")


@app.post("/login", response_class=HTMLResponse)
def login_submit(request: Request, username: str = Form(""), password: str = Form("")):
    admin_id = auth.check_credentials(username.strip(), password)
    if not admin_id:
        return render(request, "login.html", error="账号或口令不对")
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
    files = sources.list_files()
    try:
        counts = users.stats()
        user_error = ""
    except Exception as exc:
        counts = {"users": "-", "objects": "-", "sessions": "-"}
        user_error = str(exc)
    with sqlite() as conn:
        recent = conn.execute("SELECT * FROM audit ORDER BY id DESC LIMIT 12").fetchall()
    return render(request, "dashboard.html", session, files=files, counts=counts,
                  user_error=user_error, recent=recent, cfg_dir=str(CFG_DIR))


# ── 源文件 ──────────────────────────────────────────────────────────

@app.get("/sources", response_class=HTMLResponse)
def sources_page(request: Request, ok: str = "", err: str = ""):
    session = auth.require(request)
    return render(request, "sources.html", session, files=sources.list_files(), ok=ok, err=err)


@app.get("/sources/{name}/edit", response_class=HTMLResponse)
def source_edit(request: Request, name: str, ok: str = "", err: str = ""):
    session = auth.require(request)
    try:
        text = sources.read_text(name)
    except sources.SourceError as exc:
        return redirect(f"{M}/sources?err={exc}")
    return render(request, "edit.html", session, name=name, text=text,
                  backups=sources.backups(name), ok=ok, err=err,
                  readonly=name in sources.READONLY_FILES)


@app.post("/sources/{name}/save")
def source_save(request: Request, name: str, text: str = Form(""), csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    try:
        result = sources.save_text(name, text)
    except sources.SourceError as exc:
        return redirect(f"{M}/sources/{name}/edit?err={exc}")
    audit(request, session, "source.save", name, f"{result['bytes']} 字节")
    return redirect(f"{M}/sources/{name}/edit?ok=已保存")


@app.post("/sources/{name}/restore")
def source_restore(request: Request, name: str, stamp: str = Form(""), csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    try:
        result = sources.restore(name, stamp)
    except sources.SourceError as exc:
        return redirect(f"{M}/sources/{name}/edit?err={exc}")
    audit(request, session, "source.restore", name, f"from {result['restored_from']}")
    return redirect(f"{M}/sources/{name}/edit?ok=已回滚到 {stamp}")


@app.post("/sources/{name}/delete")
def source_delete(request: Request, name: str, csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    try:
        sources.delete(name)
    except sources.SourceError as exc:
        return redirect(f"{M}/sources?err={exc}")
    audit(request, session, "source.delete", name)
    return redirect(f"{M}/sources?ok=已删除 {name}（有备份）")


@app.post("/sources/new")
def source_new(request: Request, name: str = Form(""), csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    template = {
        "adapter": "cms",
        "api": "https://example.com/api.php/provide/vod/",
        "cover": {"mode": "plain", "allowedHosts": []},
    }
    try:
        sources.create(name, json.dumps(template, ensure_ascii=False, indent=2))
    except sources.SourceError as exc:
        return redirect(f"{M}/sources?err={exc}")
    audit(request, session, "source.create", name)
    return redirect(f"{M}/sources/{name}/edit?ok=已创建，接着改内容")


# ── 市场清单 ────────────────────────────────────────────────────────

@app.get("/market", response_class=HTMLResponse)
def market_page(request: Request, ok: str = "", err: str = ""):
    session = auth.require(request)
    registry = [item for item in sources.list_files() if item["kind"] == "source" and not item["error"]]
    try:
        current = json.loads(sources.read_text("market.json"))
        current_urls = {str(item.get("url", "")) for item in current.get("sources", []) if isinstance(item, dict)}
        current_items = current.get("sources", [])
    except Exception:
        current_items, current_urls = [], set()
    return render(request, "market.html", session, registry=registry,
                  current=current_items, current_urls=current_urls, ok=ok, err=err)


@app.post("/market")
def market_save(request: Request, csrf: str = Form(""), entry: list[str] = Form(default=[])):
    """按勾选的源重建 market.json。entry 里每项形如 name|url|desc。"""
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    items = []
    for raw in entry:
        try:
            name, url, desc = raw.split("|", 2)
        except ValueError:
            continue
        items.append({"name": name.strip(), "desc": desc.strip(), "url": url.strip()})
    try:
        sources.save_text("market.json", json.dumps({"updated": datetime.date.today().isoformat(), "sources": items}, ensure_ascii=False))
    except sources.SourceError as exc:
        return redirect(f"{M}/market?err={exc}")
    audit(request, session, "market.save", "market.json", f"{len(items)} 条")
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
    return render(request, "users.html", session, listing=listing, user_error=user_error, ok=ok, err=err)


@app.post("/users/{user_id}/password")
def user_password(request: Request, user_id: str, password: str = Form(""), csrf: str = Form("")):
    session = auth.require(request)
    auth.require_csrf(request, csrf)
    try:
        users.reset_password(user_id, password)
    except Exception as exc:
        return redirect(f"{M}/users?err={exc}")
    audit(request, session, "user.password", user_id)
    return redirect(f"{M}/users?ok=口令已重置，旧会话已踢下线")


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
