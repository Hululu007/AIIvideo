#!/usr/bin/env bash
#
# 发布一版前端热更新（HTML/CSS/JS）。
#
#   ./publish-hot.sh                  只生成到仓库的 hot/ 目录
#   HOT_PUSH=1 ./publish-hot.sh       生成后自动 commit + push 到 GitHub
#
# ── 2026-10-18 起改走 GitHub，不再上传自己的服务器 ──
# 以前清单和包都 scp 到香港那台，那是单点：机器欠费、被封、磁盘满、Caddy
# 配置写错，全世界的前端热更就断了。而热更恰恰是「出问题时唯一能救场的通道」，
# 把它做成单点等于没有这个东西。
#
# 现在清单和包都在仓库的 hot/ 目录里，走 GitHub 的 CDN，客户端那边通过
# Endpoints.hotIndexUrls() 的三个候选（两个 akams 镜像 + raw）去拉。
#
# ── 代价（要说清楚）──
# raw.githubusercontent 和镜像前面都有 CDN 缓存，大约 5 分钟。刚发布的一版
# 可能要等几分钟客户端才看得到。客户端 30 分钟才查一次、拿到之后下次启动
# 才应用，这个延迟不影响任何事。
#
# ── 密钥 ──
# 2026-10-18 换了一对新密钥（旧私钥丢了，本机和服务器都找不到，只有公钥）。
# 新公钥已经打进 1.0.24，所以**这个热更通道只对 1.0.24 及以后的 APK 生效**。
# 装旧版的用户收不到热更，但也不会出错 —— 验签不过就当没有更新。
#
# 可覆盖的环境变量：
#   REVISION=20261018010101   版本号，默认当前时间戳（必须单调递增）
#   VERSION=2026.10.18.1      展示用版本，默认当前时间
#   HOT_MIN_VERSION_CODE=...  低于这个 versionCode 的 App 不接收（默认 10024）
#   HOT_ASSETS=...            前端资源目录
#   HOT_OUT=...               输出目录（默认仓库的 hot/）
#   HOT_KEY=...               ECDSA 私钥（只在本地，.build/brandkey/ 不提交）
#   HOT_KEEP=2                目录里保留几个历史包
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

ASSETS="${HOT_ASSETS:-$REPO/work/android/app/src/main/assets}"
OUT="${HOT_OUT:-$REPO/hot}"
KEY="${HOT_KEY:-$HERE/.build/brandkey/aiivideo-hotupdate-private.pem}"
PUB="${HOT_PUB:-$ASSETS/hot-update-public.pem}"
BASE_URL="${HOT_BASE_URL:-https://raw.githubusercontent.com/zhaoruizhe81/AIIvideo/main/hot}"
REVISION="${REVISION:-$(date +%Y%m%d%H%M%S)}"
VERSION="${VERSION:-$(date +%Y.%m.%d.%H%M)}"
MIN_VERSION_CODE="${HOT_MIN_VERSION_CODE:-10024}"
KEEP="${HOT_KEEP:-2}"
PUSH="${HOT_PUSH:-0}"

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m ok\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR\033[0m %s\n' "$*" >&2; exit 1; }

[ -f "$ASSETS/app.html" ] || die "找不到 $ASSETS/app.html"
[ -f "$KEY" ] || die "找不到私钥 $KEY。它在 .build/brandkey/，不入库；丢了就得换新密钥并重发 APK。"
[ -f "$PUB" ] || die "找不到公钥 $PUB"
command -v zip >/dev/null || die "缺 zip"
command -v openssl >/dev/null || die "缺 openssl"
case "$BASE_URL" in https://*) ;; *) die "HOT_BASE_URL 必须是 https://" ;; esac
case "$REVISION" in ''|*[!0-9]*) die "REVISION 必须是整数：$REVISION" ;; esac
[ "$REVISION" -ge 15 ] || die "REVISION 必须 >= 15（BUNDLED_UI_REVISION）"

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/aii-hot.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

say "整理前端文件"
mkdir -p "$STAGE/ui"
cp -R "$ASSETS"/. "$STAGE/ui/"
# 这几样不能被热更新覆盖：
#   hot-update-public.pem  resource() 里明确跳过，而且 stage 也不收 .pem
#   site-default.json      resource() 里明确跳过；站点配置走根目录 site.json
#   dexopt/                编译产物，不属于网页资源
#   .DS_Store              垃圾
rm -rf "$STAGE/ui/dexopt" "$STAGE/ui/hot-update-public.pem" \
       "$STAGE/ui/site-default.json" "$STAGE/ui/hot-runtime.js"
find "$STAGE/ui" -name '.DS_Store' -delete
# site-web.js 是 site-default.json 的派生产物，**必须现算一份**。
#
# 不能直接用 assets 里那份：Gradle 的 genWebConfig 按内容哈希判 UP-TO-DATE，
# 内容没变就不重写文件，于是磁盘上那份的 mtime 可能比 site-default.json 旧。
# 拿 mtime 判断会误报，拿内容判断要再写一个比较器 —— 都不如现算。
GEN="$REPO/work/android/scripts/gen-web-config.py"
[ -f "$GEN" ] || die "找不到 $GEN"
python3 "$GEN" "$ASSETS/site-default.json" "$STAGE/ui/site-web.js" >/dev/null \
  || die "site-web.js 生成失败"
printf '{}\n' > "$STAGE/site.json"
printf '{"enabled":false}\n' > "$STAGE/theme.json"
ok "ui $(find "$STAGE/ui" -type f | wc -l | tr -d ' ') 个文件"

say "打包 resources-r$REVISION.zip"
mkdir -p "$OUT"
ZIP="$OUT/resources-r$REVISION.zip"
( cd "$STAGE" && zip -r -X -q "$ZIP" ui site.json theme.json )

SIZE=$(wc -c < "$ZIP" | tr -d ' ')
SHA=$(shasum -a 256 "$ZIP" | awk '{print $1}')
URL="$BASE_URL/resources-r$REVISION.zip"
[ "$SIZE" -le 12582912 ] || die "zip 超过 12MB：$SIZE"

say "写清单 payload"
python3 - "$STAGE" "$REVISION" "$VERSION" "$MIN_VERSION_CODE" "$SIZE" "$SHA" "$URL" <<'PY'
import json, sys
stage, revision, version, min_code, size, sha, url = sys.argv[1:]
payload = {
    "schema": 1,
    "revision": int(revision),
    "version": version,
    "minVersionCode": int(min_code),
    "maxVersionCode": 2147483647,
    "bridgeVersion": 1,
    "contentModeVersion": 1,
    "size": int(size),
    "sha256": sha,
    "url": url,
}
with open(stage + "/payload.json", "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
PY

say "签名"
openssl dgst -sha256 -sign "$KEY" -out "$STAGE/signature.bin" "$STAGE/payload.json"
openssl dgst -sha256 -verify "$PUB" -signature "$STAGE/signature.bin" "$STAGE/payload.json" >/dev/null \
  || die "签名自检失败：私钥 $KEY 和 APK 里的公钥 $PUB 不配对"
python3 - "$STAGE" "$OUT/hot-update.json" <<'PY'
import base64, json, sys
stage, target = sys.argv[1:]
payload = open(stage + "/payload.json", "rb").read()
signature = open(stage + "/signature.bin", "rb").read()
envelope = {
    "payload": base64.b64encode(payload).decode("ascii"),
    "signature": base64.b64encode(signature).decode("ascii"),
}
with open(target, "w", encoding="utf-8") as handle:
    json.dump(envelope, handle, ensure_ascii=False, separators=(",", ":"))
PY
ok "hot-update.json $(wc -c < "$OUT/hot-update.json" | tr -d ' ') 字节"

say "核对包内容"
python3 - "$ZIP" <<'PY'
import sys, zipfile, re
names = set(zipfile.ZipFile(sys.argv[1]).namelist())
for required in ("site.json", "theme.json", "ui/app.html", "ui/app.js", "ui/app.css"):
    if required not in names:
        raise SystemExit("缺少 " + required)
bad = [n for n in names if not n.endswith("/") and not re.match(
    r"(?:ui/|theme/).*|site\.json|theme\.json", n)]
if bad:
    raise SystemExit("stage() 会拒收这些路径：" + ", ".join(bad[:5]))
print("entry", len(names))
PY
ok "manifest + zip 自检通过"

# 只留最近几个包。仓库里堆 340KB 的 zip 是会被 git 历史永久记住的，
# 但工作区（浅克隆）不该一直涨。
say "清理旧包（保留最近 $KEEP 个）"
ls -1t "$OUT"/resources-r*.zip 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
ls -1 "$OUT"

if [ "$PUSH" = "1" ]; then
  say "提交并推送"
  cd "$REPO"
  git add hot
  git commit -q -m "前端热更 r$REVISION（$VERSION）" || die "没有变化可提交？"
  git push origin main
  ok "已推送"
  echo
  echo "  客户端要等 CDN 缓存过期（约 5 分钟）才拿得到这一版。"
fi

cat <<EOF

  revision : $REVISION
  version  : $VERSION
  zip      : $ZIP
  size     : $SIZE bytes
  sha256   : $SHA
  manifest : $OUT/hot-update.json
  index    : $BASE_URL/hot-update.json
  minVer   : ${MIN_VERSION_CODE} —— 低于这个 versionCode 的 App 不接收

  $([ "$PUSH" = "1" ] && echo '已推送。' || echo '未推送；加 HOT_PUSH=1 直接 commit + push。')
EOF
