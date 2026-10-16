#!/usr/bin/env python3
"""
黄果短剧 Web API 客户端 —— 无鉴权 REST 接口封装（第三方客户端数据层）

接口清单（全部 GET，无签名、无 cookie、无登录态）:
    /api/home                    首页聚合  banners/featured/latest/cate_rec/topic_rec
    /api/home/latest?page=N      最新更新分页
    /api/videos?page=N           全量列表分页  pagination: page/pages/size/total
    /api/videos/<id>             剧集详情 + episodes[]
    /api/videos/<id>/play?ep=N   单集播放地址  video_url = HLS m3u8（带时签）
    /api/search?q=<kw>           向量搜索
    /api/tags                    分类树
    /api/topics                  专辑

播放地址为带 auth_key 的 HLS，分片 AES-128，key URL 与 IV 均在 m3u8 内部 ——
播放器（ExoPlayer / libmpv / AVPlayer）原生支持，无需自行解密。

用法:
    python3 drama_api.py home
    python3 drama_api.py list 2
    python3 drama_api.py detail 12
    python3 drama_api.py play 12 3
    python3 drama_api.py search 健身
    python3 drama_api.py tags
    python3 drama_api.py probe          # 域名可用性
"""
import json
import os
import re
import ssl
import sys
import time
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
HERE = os.path.dirname(os.path.abspath(__file__))

# ---- 域名发现 ---------------------------------------------------------------
# 站点域名会漂移；接口路径与响应 schema 不随域变化（已实测 huangguoai.com vs
# d73.pqhybuxo.cc：/video/8627/ 与 /api/videos/8627/rating 完全一致）。
PERMANENT = ["https://huangguoai.com", "https://huangguoai.ai"]
PUBLISH_PAGES = ["https://huangguoai.pages.dev", "https://huang-guo.pages.dev"]
PROBE_ID = "12"  # 任何存在的剧 id，用于探活


def _http(url, referer=None, timeout=15, binary=False):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Referer": referer or (url.split("/api/")[0] + "/"),
        "Accept": "application/json, text/plain, */*",
    })
    data = urllib.request.urlopen(req, context=CTX, timeout=timeout).read()
    return data if binary else data.decode("utf-8", "replace")


def _alive(base, timeout=8):
    """能被 /api/videos/<PROBE_ID> 返回合法 JSON 的域名才算可用。"""
    try:
        d = json.loads(_http(f"{base}/api/videos/{PROBE_ID}", timeout=timeout))
        return d.get("status") == 1 and isinstance(d.get("data"), dict)
    except Exception:
        return False


def candidates(include_mirrors=True):
    out, bases = list(PERMANENT), []
    for page in PUBLISH_PAGES:
        try:
            js = _http(page.rstrip("/") + "/publish.js", referer=page + "/")
        except Exception:
            continue
        bases += re.findall(r"'([a-z0-9-]+\.(?:cc|com|net|dev|ai|top))/?'", js)
        out += re.findall(r"'(https://[a-z0-9.-]+)'", js)
    if include_mirrors:
        subs = ["thu", "pku", "fdu", "sjtu", "zju", "nju", "d73", "a1b"]
        for b in bases[:4]:
            for s in subs[:6]:
                out.append(f"https://{s}.{b.strip('/')}")
    seen, uniq = set(), []
    for u in out:
        u = u.rstrip("/")
        if u and u not in seen:
            seen.add(u)
            uniq.append(u)
    return uniq


class DramaAPI:
    """域名自动发现 + 失效自动切换的短剧 API 客户端。"""

    CACHE = os.path.join(HERE, ".drama_base")

    def __init__(self, base=None, verbose=False):
        self.verbose = verbose
        self.base = base.rstrip("/") if base else self._discover()
        self._tried = set()

    # ---- 域名 ----
    def _discover(self):
        if os.path.exists(self.CACHE):
            cached = open(self.CACHE).read().strip()
            if cached and _alive(cached):
                self._log(f"[domain] 缓存可用 {cached}")
                return cached
        for c in candidates():
            if _alive(c):
                try:
                    open(self.CACHE, "w").write(c)
                except Exception:
                    pass
                self._log(f"[domain] 选定 {c}")
                return c
        return PERMANENT[0]

    def _failover(self):
        """当前域名失效：换一个候选。"""
        self._tried.add(self.base)
        for c in candidates():
            if c not in self._tried and _alive(c):
                self._log(f"[domain] 切换 {self.base} -> {c}")
                self.base = c
                try:
                    open(self.CACHE, "w").write(c)
                except Exception:
                    pass
                return True
        return False

    def _log(self, m):
        if self.verbose:
            print(m, file=sys.stderr)

    # ---- 请求 ----
    def get(self, path, retry=True, **params):
        q = ("?" + urllib.parse.urlencode(params)) if params else ""
        url = f"{self.base}{path}{q}"
        try:
            return json.loads(_http(url))
        except Exception as e:
            if retry and self._failover():
                return self.get(path, retry=False, **params)
            raise RuntimeError(f"请求失败 {path}: {e}")

    # ---- 接口 ----
    def home(self):
        return self.get("/api/home")["data"]

    def latest(self, page=1):
        return self.get("/api/home/latest", page=page)["data"]

    def videos(self, page=1):
        """全量列表。返回 (items, pagination)。"""
        d = self.get("/api/videos", page=page)["data"]
        return d["items"], d["pagination"]

    def detail(self, vid):
        return self.get(f"/api/videos/{vid}")["data"]

    def play(self, vid, ep=1):
        """单集播放信息，data['video_url'] 为带时签的 m3u8。"""
        return self.get(f"/api/videos/{vid}/play", ep=ep)["data"]

    def search(self, kw):
        return self.get("/api/search", q=kw)["data"]

    def tags(self):
        return self.get("/api/tags")["data"]

    def topics(self):
        return self.get("/api/topics")["data"]

    # ---- 便捷 ----
    def series(self, vid, limit=None):
        """把一部剧展开成可直接喂给播放器的集列表。"""
        d = self.detail(vid)
        out = []
        for e in d.get("episodes", []):
            out.append({
                "video_id": d["id"],
                "title": d.get("title"),
                "cover": d.get("cover"),
                "ep": e["ep_num"],
                "ep_title": e.get("title"),
                "duration": e.get("duration"),
            })
            if limit and len(out) >= limit:
                break
        return d, out

    def resolve(self, vid, ep):
        """播放入口：每次实时取，auth_key 是时签不能缓存复用。"""
        p = self.play(vid, ep)
        return {
            "video_id": vid, "ep": p.get("ep_num"), "duration": p.get("duration"),
            "url": p.get("video_url"), "title": p.get("page_h1") or p.get("title"),
        }


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return
    api = DramaAPI(verbose=True)
    cmd = a[0]

    if cmd == "probe":
        print(f"当前域名: {api.base}  (可用)")
    elif cmd == "home":
        d = api.home()
        print(f"banners={len(d.get('banners',[]))} featured={len(d.get('featured',[]))} "
              f"latest={len(d.get('latest',[]))} cate_rec={len(d.get('cate_rec',[]))} "
              f"topic_rec={len(d.get('topic_rec',[]))}")
        for v in d.get("featured", [])[:6]:
            print(f"  featured  {v['id']:>6}  {v['title'][:28]:<30} {v.get('score')}  {v.get('episode_count')}集")
        for v in d.get("latest", [])[:6]:
            print(f"  latest    {v['id']:>6}  {v['title'][:28]:<30} {v.get('score')}")
    elif cmd == "list":
        page = int(a[1]) if len(a) > 1 else 1
        items, pg = api.videos(page)
        print(f"page={pg['page']}/{pg['pages']} size={pg['size']} total={pg['total']}")
        for v in items[:8]:
            print(f"  {v['id']:>6}  {str(v.get('title'))[:30]:<32} {v.get('score')} {v.get('episode_count')}集")
    elif cmd == "detail":
        d = api.detail(a[1])
        print(f"{d['id']} {d['title']}  score={d.get('score')} eps={d.get('episode_count')} "
              f"dur={d.get('duration')} type={d.get('video_type')} adult={d.get('is_adult')}")
        print(f"  封面 {str(d.get('cover'))[:110]}")
        print(f"  video_url {str(d.get('video_url'))[:110]}")
        for e in d.get("episodes", [])[:5]:
            print(f"    ep{e['ep_num']:>3}  {e.get('duration')}s  status={e.get('status')}")
    elif cmd == "play":
        r = api.resolve(a[1], int(a[2]) if len(a) > 2 else 1)
        print(f"id={r['video_id']} ep={r['ep']} dur={r['duration']}")
        print(f"  {r['title']}")
        print(f"  {r['url']}")
    elif cmd == "search":
        d = api.search(a[1])
        print(f"engine={d.get('engine')} items={len(d.get('items',[]))} "
              f"total={d.get('total') or d.get('pagination')}")
        for v in d.get("items", [])[:8]:
            print(f"  {v.get('id'):>6}  {str(v.get('title'))[:32]:<34} {v.get('score')}")
    elif cmd == "tags":
        d = api.tags()
        for c in d.get("categories", []):
            names = ",".join(t["name"] for t in c.get("tags", [])[:12])
            print(f"  [{c['id']}] {c['name']} ({len(c.get('tags',[]))}) {names}")
    elif cmd == "topics":
        d = api.topics()
        for t in d.get("items", []):
            print(f"  {t['id']:>4}  {t['title'][:28]:<30} {t.get('video_count')} 部  /topics/{t.get('slug')}/")
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
