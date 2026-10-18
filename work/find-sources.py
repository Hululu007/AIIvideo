#!/usr/bin/env python3
"""批量找「还活着、还快」的苹果CMS 源，按首片耗时排序。

    python3 find-sources.py <仓地址或接口地址> [...]
    python3 find-sources.py --file seeds.txt --out sources.json --jobs 16

三种输入自动识别：

    ① TVBox 多仓     {"urls":[{"url":…,"name":…}]}   逐个展开子仓
    ② TVBox 单仓     {"sites":[{"api":…,"name":…}]}  只抽苹果CMS 的 api
    ③ 苹果CMS 接口    {"code":1,"list":[…],"class":[…]}  直接探

判活三层 + 一次测速，缺一层就会误判（单源版见 check-source.py）：

    ① ?ac=list 里真有 list        → 接口活着
    ② ?ac=detail&ids= 里有 m3u8     → 有直链
    ③ 那个 m3u8 真的 200 + #EXTM3U  → 才算能用
    ④ 拉 m3u8、取第一个分片、只下 256KB → 记下「首片毫秒」和吞吐

第 ④ 步是这个脚本的重点。接口延迟骗人：`?ac=list` 780ms 里有 570ms 是
TCP+TLS 握手，服务端出数只有 200ms；而用户真正等的是首片那 1-2 秒。
排序按首片耗时，不按接口耗时。

输出末尾附一份 sources.json —— 直接填进 App 设置页，会列成勾选框。
"""
import argparse
import json
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
      "Chrome/131.0.0.0 Mobile Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
TIMEOUT = 12
SEG_TIMEOUT = 8       # 分片只测 256KB，慢源别把整轮拖死
SEG_BYTES = 262144
NODE_LABEL = "本机"
SAMPLE = 3            # 每个源抽几部验 m3u8。3 部够看出死活。

def log(msg):
    print(msg, flush=True)

def fetch(url, timeout=TIMEOUT, limit=None, range_bytes=None):
    """→ (status, bytes|str, 毫秒, ttfb 毫秒)。

    limit / range_bytes 都是「只要前面一段」：分片动辄 900KB，全下会把一轮
    探测拖成几分钟，而比快慢 256KB 就够了。
    """
    started = time.time()
    headers = {"User-Agent": UA, "Connection": "close"}
    if range_bytes:
        headers["Range"] = "bytes=0-%d" % (range_bytes - 1)
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            ttfb = int((time.time() - started) * 1000)
            if range_bytes:
                data = r.read(range_bytes)
            elif limit:
                data = r.read(limit)
            else:
                data = r.read()
            body = data if isinstance(data, bytes) else data
            return r.status, body, int((time.time() - started) * 1000), ttfb
    except urllib.error.HTTPError as e:
        return e.code, b"", int((time.time() - started) * 1000), -1
    except Exception as e:  # noqa: BLE001
        return None, repr(e), int((time.time() - started) * 1000), -1

def text(body):
    if isinstance(body, bytes):
        return body.decode("utf-8", "replace")
    return body or ""

# ── 展开：仓 → 一堆接口 ────────────────────────────────────────────────

def looks_like_cms(body):
    try:
        parsed = json.loads(text(body))
    except Exception:
        return False
    return isinstance(parsed, dict) and "list" in parsed and "class" in parsed

def expand(url, depth=0, seen=None):
    if depth > 3 or not url:
        return []
    seen = seen if seen is not None else set()
    if url in seen:
        return []
    seen.add(url)

    status, body, _, _ = fetch(url)
    if status != 200:
        return []
    try:
        parsed = json.loads(text(body))
    except Exception:
        return []

    if isinstance(parsed, dict) and isinstance(parsed.get("urls"), list):
        out = []
        for item in parsed["urls"]:
            child = item.get("url") if isinstance(item, dict) else item
            if isinstance(child, str):
                out += expand(child, depth + 1, seen)
        return out

    if isinstance(parsed, dict) and isinstance(parsed.get("sites"), list):
        out = []
        for site in parsed["sites"]:
            api = site.get("api") if isinstance(site, dict) else None
            if not isinstance(api, str):
                continue
            # type=1 是苹果CMS；有的仓不写 type，靠 api 里有 provide/vod 认。
            if site.get("type") == 1 or "provide/vod" in api:
                out.append(api)
        return out

    if looks_like_cms(body):
        return [url]
    return []

def normalize(api):
    api = api.strip()
    if not re.match(r"^https?://", api):
        return ""
    if "?" in api:
        api = api.split("?")[0]
    if not api.endswith("/"):
        api += "/"
    return api

# ── 探测 ────────────────────────────────────────────────────────────────

def ids_and_classes(body):
    try:
        parsed = json.loads(text(body))
    except Exception:
        return [], 0
    items = parsed.get("list") or []
    classes = parsed.get("class") or []
    return [str(x.get("vod_id")) for x in items if isinstance(x, dict)], len(classes)

def play_urls(body):
    """detail 里的播放直链。

    JSON 里斜杠是转义的（先还原）；detail 里还有封面图，必须按后缀过滤，
    否则会拿 jpg 当 m3u8 试。
    """
    body = text(body).replace("\\/", "/")
    found = re.findall(r"https?://[^\s\"'<>\]$,\\]+", body)
    return [u for u in found if ".m3u8" in u or ".mp4" in u]

def first_segment(m3u8_url, m3u8_text):
    """主清单里第一个分片。相对路径要拼 —— 有的站回的是相对地址。"""
    for line in m3u8_text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        return urllib.parse.urljoin(m3u8_url, line)
    return None

def measure(m3u8):
    """→ (首片毫秒, kbps)。失败给 (0, 0)。"""
    status, body, cost, ttfb = fetch(m3u8, timeout=SEG_TIMEOUT, limit=262144)
    if status != 200:
        return 0, 0
    seg = first_segment(m3u8, text(body))
    if not seg:
        return 0, 0
    st, data, seg_cost, seg_ttfb = fetch(seg, timeout=SEG_TIMEOUT, range_bytes=SEG_BYTES)
    if st not in (200, 206) or not data:
        return 0, 0
    kbps = int(len(data) * 8 / max(1, seg_cost))
    return seg_cost, kbps

def probe(api):
    api = normalize(api)
    result = {"api": api, "classes": 0, "items": 0, "alive": 0,
              "tested": 0, "ms": 0, "seg_ms": 0, "kbps": 0, "note": ""}
    if not api:
        result["note"] = "不是 http(s) 地址"
        return result

    status, body, ms, _ = fetch(api + "?ac=list")
    result["ms"] = ms
    if status != 200:
        result["note"] = "HTTP %s" % status
        return result

    ids, classes = ids_and_classes(body)
    result["classes"] = classes
    result["items"] = len(ids)
    if not ids:
        result["note"] = "list 为空"
        return result
    if classes == 0:
        result["note"] = "无 class（建不出分类表）"
        return result

    good = 0
    seg_total = 0
    kbps_best = 0
    for vid in ids[:SAMPLE]:
        _, detail, _, _ = fetch("%s?ac=detail&ids=%s" % (api, vid))
        urls = play_urls(detail)
        result["tested"] += 1
        if not urls:
            continue
        st, media, _, _ = fetch(urls[0], timeout=SEG_TIMEOUT, limit=262144)
        if st == 200 and text(media).lstrip().startswith("#EXTM3U"):
            good += 1
            seg_ms, kbps = measure(urls[0])
            if seg_ms:
                seg_total += seg_ms
                kbps_best = max(kbps_best, kbps)
    result["alive"] = good
    if good:
        result["seg_ms"] = int(seg_total / good)
        result["kbps"] = kbps_best
    if good == 0:
        result["note"] = "detail 里的地址全 404"
    elif good < result["tested"]:
        result["note"] = "半活"
    else:
        result["note"] = "全活"
    return result

def rate_of(r):
    return 0 if not r["tested"] else round(r["alive"] * 100 / r["tested"])

def score(r):
    """活率优先，其次首片耗时，再其次吞吐。

    不按接口延迟排：780ms 的接口里有 570ms 是握手，第一次之后就走连接池了，
    而首片那 1-2 秒是每一集都要付的。
    """
    return (rate_of(r), -(r["seg_ms"] or 99999), r["kbps"])

# ── 输出 ────────────────────────────────────────────────────────────────

def render(rows):
    rows = sorted(rows, key=score, reverse=True)
    log("")
    log("| 活率 | 首片 | 吞吐 | 分类 | 接口 | 状态 |")
    log("|---|---|---|---|---|---|")
    for r in rows:
        log("| %s%% | %s | %s | %d | %s | %s |" % (
            rate_of(r),
            ("%dms" % r["seg_ms"]) if r["seg_ms"] else "-",
            ("%dkbps" % r["kbps"]) if r["kbps"] else "-",
            r["classes"], r["api"] or "(空)", r["note"] or "-"))
    return rows

def site_name(api):
    """抓源站首页的 <title> 当名字。

    域名（www.hongniuzy2.com）对用户是零信息量。站点自己在 <title> 里写的
    那个名字（「暴风影音」「星辰影视」）才是他认得的。抓不到就算了 ——
    退回「线路 N」这种中性名，别把域名顶上去。
    """
    try:
        parts = urllib.parse.urlsplit(api)
        root = "%s://%s/" % (parts.scheme, parts.netloc)
        status, body, _, _ = fetch(root, timeout=6, limit=65536)
        if status != 200:
            return ""
        html = text(body)
        match = re.search(r"<title[^>]*>(.*?)</title>", html, re.I | re.S)
        if not match:
            return ""
        name = re.sub(r"\s+", " ", match.group(1)).strip()
        # 站点常在标题里堆关键词，取第一个分隔符之前那段。
        for sep in ("-", "_", "|", "—", "·", " "):
            if sep in name:
                head = name.split(sep)[0].strip()
                if 2 <= len(head) <= 14:
                    name = head
                    break
        if not name or len(name) > 14:
            return ""
        # 纯英文/域名样的一律不要，用户一样看不懂。
        if re.fullmatch(r"[\w.\-:/]+", name):
            return ""
        return name
    except Exception:  # noqa: BLE001
        return ""

def market_json(rows, path, extra=None, top=2):
    usable = [r for r in rows if rate_of(r) >= 50]
    sources = []
    seen_hosts = set()
    for r in usable:
        host = re.sub(r"^https?://", "", r["api"]).split("/")[0]
        # 同一个站常有两个入口（http/https、带 from/ 的），展开后会重复。
        # 已经按首片排好序，留第一个就行。
        if host in seen_hosts:
            continue
        seen_hosts.add(host)
        # 接口域名对用户是零信息量（而且这些站的根域根本没有网页，抓不到 title）。
        # 统一叫「影视线路 N」，desc 写分类数，够他挑。
        name = "影视线路 %d" % (len(sources) + 1)
        # 描述只说「有多少分类」。**不写测速结果** —— 那个数是香港 VPS 测的，
        # 手机上完全不是这个数。用户看到「首片 0.1s」选了它，实际卡起来
        # 只会觉得 App 有毛病。速度留在脚本里用来挑哪 2 个入选就够了。
        desc = "%d 个分类" % r["classes"]
        sources.append({"name": name, "desc": desc, "url": r["api"]})
        # 给用户看的清单不需要 18 个：两个换着用就够，多了只会选不出来。
        if len(sources) >= top:
            break
    # 固定条目（hanime / agedm / 自家配置）垫在后面：它们不是苹果CMS，
    # 探测管道处理不了，但用户照样应该能在清单里勾到。
    seen = {s["url"] for s in sources}
    for item in (extra or []):
        if isinstance(item, dict) and item.get("url") and item["url"] not in seen:
            sources.append(item)
            seen.add(item["url"])
    payload = {"updated": time.strftime("%Y-%m-%d"), "sources": sources}
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
    log("")
    log("已写 %s（%d 个可用源，按首片耗时排好）" % (path, len(sources)))

def main():
    parser = argparse.ArgumentParser(description="批量探测苹果CMS 源，按首片耗时排序")
    parser.add_argument("targets", nargs="*", help="仓地址或接口地址")
    parser.add_argument("--file", help="一行一个地址")
    parser.add_argument("--out", default="sources.json", help="市场清单输出")
    parser.add_argument("--jobs", type=int, default=12, help="并发数")
    parser.add_argument("--node", default="本机",
                        help="测速节点名，只写进 desc（如 HK / 手机），不影响逻辑")
    parser.add_argument("--extra",
                        help="固定条目文件 {sources:[…]}，原样追加到输出末尾")
    parser.add_argument("--top", type=int, default=2,
                        help="清单里放几个探测出来的源（默认 5）")
    args = parser.parse_args()
    global NODE_LABEL
    NODE_LABEL = args.node or "本机"

    seeds = list(args.targets)
    if args.file:
        with open(args.file, encoding="utf-8") as handle:
            seeds += [line.strip() for line in handle
                      if line.strip() and not line.startswith("#")]
    if not seeds:
        parser.print_help()
        return 2

    log("展开 %d 个入口…" % len(seeds))
    apis = []
    for seed in seeds:
        found = expand(seed)
        log("  %s → %d 个接口" % (seed[:70], len(found)))
        apis += found

    uniq = []
    dropped = 0
    for api in apis:
        api = normalize(api)
        if not api or api in uniq or "provide/vod" not in api:
            continue
        # 只要 https。http 的源在手机上有两层不确定性：明文会被运营商插广告/劫持，
        # 域名还容易被 DNS 污染指到空 IP。用户分不清是「这个源慢」还是「被墙了」，
        # 所以干脆不进清单。
        if not api.startswith("https://"):
            dropped += 1
            continue
        uniq.append(api)
    if dropped:
        log("  丢掉 %d 个 http 源（只要 https）" % dropped)
    if not uniq:
        log("一个接口都没展开出来。仓地址要指向 JSON（多仓/单仓），不是网页。")
        return 1
    log("去重后 %d 个接口，开始探测（并发 %d）…" % (len(uniq), args.jobs))

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        rows = list(pool.map(probe, uniq))

    rows = render(rows)
    extra = []
    if args.extra:
        try:
            with open(args.extra, encoding="utf-8") as handle:
                extra = json.load(handle).get("sources") or []
        except Exception as error:  # noqa: BLE001
            log("固定条目读不到（%s），跳过：%s" % (args.extra, error))
    market_json(rows, args.out, extra, max(1, args.top))
    log("")
    log("活率 <50% 的没写进清单。首片最快的排最前面，先试那几个。")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
