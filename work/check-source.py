#!/usr/bin/env python3
"""判断一个内容源是不是真能用。

    python3 check-source.py https://example.com/api.php/provide/vod/at/xml/

── 为什么要专门跑这一步 ─────────────────────────────────────────────
列表能拉 ≠ 能看。最常见的死法是**接口活着、片子没了**：采集站的列表常常是
缓存下来的，`?ac=detail` 也照常吐地址，但那些 m3u8 早就 404 了。
只看列表就换源，换过去点开每一集都是「播不了」。

所以判活要分三层，缺一层都会误判：

    ① 列表有返回           → 接口还活着
    ② detail 里有 m3u8     → 有直链
    ③ **curl 那个 m3u8**   → 200 才算真活     ← 这一步最关键，也最常被跳过

这个脚本三层都跑，最后给一句能不能用。

── 两个踩过的坑（都在这脚本里栽过）──────────────────────────────────
· 接口不带 /at/xml/ 时默认回 **JSON**，不是 XML。只按 <id> 正则去匹配会一条都
  抓不到，然后判成「空壳」—— 活站就这么被误杀了。
· JSON 里斜杠是转义的（反斜杠 + 斜杠）。不先还原，正则撞到第一个反斜杠就断。
两种格式都认，省得再踩。
"""
import json
import re
import ssl
import sys
import urllib.error
import urllib.request

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
TIMEOUT = 15


def fetch(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT, context=CTX) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return None, str(e)


def ids_of(body):
    """条目 id。JSON 和 XML 两种路线都认 —— 不带 /at/xml/ 时默认回 JSON。"""
    try:
        return [str(x.get("vod_id")) for x in (json.loads(body).get("list") or [])]
    except Exception:
        return re.findall(r"<id>(\d+)</id>", body)


def urls_of(body):
    """detail 里的**播放直链**。

    ⚠️ 两个坑：
      · JSON 路线的斜杠是转义的，不还原抓不到；
      · detail 里还有封面图（.../upload/vod/xxx.jpg），**必须按后缀过滤**，
        不然会拿封面去当播放地址试，全是「HTTP 200 但不是 m3u8」。
    """
    body = body.replace("\\/", "/")
    found = re.findall(r"https?://[^\s\"'<>\]$,\\]+", body)
    return [u for u in found if ".m3u8" in u or ".mp4" in u]


def probe_cms(base):
    """苹果CMS 那套接口：?ac=list / ?ac=detail&ids="""
    print(f"① 列表 {base}?ac=list")
    st, body = fetch(base + "?ac=list")
    if st != 200:
        print(f"   ✗ HTTP {st}  {body[:80]}")
        return False
    ids = ids_of(body)
    print(f"   ok HTTP 200  {len(body)} 字节  {len(ids)} 条")
    if not ids:
        print("   ✗ 拿不到条目 id")
        return False

    # 抽 6 部 —— 抽 3 部太少：遇到半活的站（比如暴风 3/6）正好全抽到死的，
    # 就会判成「不能用」。而且这里报的是**活率**不是「能/不能」，
    # 半活这种事得让看得见的人自己判断。
    print("②③ 抽 6 部，逐部真拉一次 m3u8")
    alive = total = 0
    for i in ids[:6]:
        _, d = fetch(f"{base}?ac=detail&ids={i}")
        us = urls_of(d)
        if not us:
            total += 1
            print(f"   ✗ id={i}  detail 里就没有 m3u8")
            continue
        u = us[0]
        st, body = fetch(u)
        ok = st == 200 and body.lstrip().startswith("#EXTM3U")
        alive += ok
        total += 1
        print(f"   {'ok' if ok else '✗ '} HTTP {st}  {u[:64]}")
    rate = round(alive / total * 100) if total else 0
    print(f"\n   活 {alive}/{total}  （{rate}%）")
    if rate == 100: print("   → 全活")
    elif rate >= 50: print("   → 半活，能用但会有片子点不开")
    elif alive: print("   → 大部分死了，凑合用")
    else: print("   → 空壳")
    return alive > 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    base = sys.argv[1].rstrip("/")
    # 有的采集站只给到 api.php 那一层
    if "/api.php" not in base:
        print("提示：这看着不像苹果CMS 的接口地址（没有 api.php），下面按原样探。\n")
    ok = probe_cms(base)
    print("\n=== " + ("能用" if ok else "不能用（或者只剩一个空壳）") + " ===")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
