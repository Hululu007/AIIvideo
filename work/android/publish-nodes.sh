#!/usr/bin/env bash
#
# 发布免费节点池（热更新）。
#
#   ./publish-nodes.sh            只检查并上传到自建镜像
#   ./publish-nodes.sh --dry-run  只检查，不上传
#
# 为什么需要这个脚本：免费节点是会烂的。App 里那份是可热更新的
# （见 NodesFeed.java），但热更新的前提是「远端那份是新的」——
# 没有这个脚本，改完 free-nodes.json 很容易忘了发出去，用户拿到的还是旧的。
#
# ── 一次发布做两件事 ──
#   1. 把 assets/free-nodes.json 传到香港服务器（Endpoints 第一条候选）
#   2. 提醒你 git push（第一条候选是 GitHub raw，源头就是仓库里的这个文件）
#
# 两件事都做了才有冗余：只剩一条能用的路径时，那条一挂就没有退路了。
#
# 可覆盖的环境变量：
#   NODES_FILE=...        默认 app/src/main/assets/free-nodes.json
#   NODES_REMOTE=relay                ssh 别名（香港那台，见 ~/.ssh/config）
#   NODES_REMOTE_DIR=/srv/cfg/nodes   远端目录（Caddy 的 file_server 直接读）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILE="${NODES_FILE:-$HERE/app/src/main/assets/free-nodes.json}"
REMOTE="${NODES_REMOTE:-relay}"
REMOTE_DIR="${NODES_REMOTE_DIR:-/srv/cfg/nodes}"
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

[ -f "$FILE" ] || { echo "找不到节点文件：$FILE" >&2; exit 1; }

# ── 先校验，再上传 ──
# 传一份坏的上去会把所有用户的缓存都写成坏的（客户端那边虽然有同样的校验，
# 但让它在源头就失败，比让十万台手机各自拒绝一次干净）。
echo "校验 $FILE ..."
python3 - "$FILE" <<'PY'
import json, sys, re

path = sys.argv[1]
raw = open(path, encoding='utf-8').read()
try:
    root = json.loads(raw)
except Exception as e:
    sys.exit(f"不是合法 JSON：{e}")

links = root.get('links')
if not isinstance(links, list):
    sys.exit("没有 links 数组")

links = [x.strip() for x in links if isinstance(x, str) and x.strip()]
if len(links) < 3:
    sys.exit(f"只有 {len(links)} 条，少于底线 3 条")

# 只统计协议分布，不重复实现客户端那套解析 —— 那是 XrayConfig 的事，
# 在这里抄一遍迟早会和它不一致。
schemes = {}
for line in links:
    m = re.match(r'^([a-z0-9]+)://', line, re.I)
    schemes[m.group(1).lower() if m else '(认不出)'] = \
        schemes.get(m.group(1).lower() if m else '(认不出)', 0) + 1

unsupported = sum(v for k, v in schemes.items() if k in ('hysteria2', 'tuic', 'ssr'))
print(f"  updated = {root.get('updated', '(缺失)')}")
print(f"  链接    = {len(links)} 条")
print(f"  协议    = {', '.join(f'{k}:{v}' for k, v in sorted(schemes.items()))}")
if unsupported:
    print(f"  注意：{unsupported} 条是核心不支持的协议（hysteria2 / tuic / ssr），客户端会静默跳过")
if len(links) - unsupported < 3:
    sys.exit("去掉不支持的协议后不足 3 条 —— 客户端会拒绝这份数据")
PY

if [ "$DRY" = "1" ]; then
    echo "dry-run，不上传。"
    exit 0
fi

echo "上传到 $REMOTE:$REMOTE_DIR/free-nodes.json ..."
ssh "$REMOTE" "mkdir -p $REMOTE_DIR"
scp -q "$FILE" "$REMOTE:$REMOTE_DIR/free-nodes.json"

# 立刻回读一次。上传成功不等于对外能拿到 —— 权限、nginx 配置、防火墙
# 都可能拦在中间，而这三样在客户端看来都长一个样子（拿不到）。
URL="${NODES_URL:-https://149.104.21.177.sslip.io/nodes/free-nodes.json}"
echo "回读 $URL ..."
code=$(curl -s -o /tmp/nodes-check.json -w '%{http_code}' --max-time 15 "$URL" || echo 000)
if [ "$code" != "200" ]; then
    echo "回读失败：HTTP $code" >&2
    exit 1
fi
local_md5=$(md5sum "$FILE" 2>/dev/null | cut -d' ' -f1 || md5 -q "$FILE")
remote_md5=$(md5sum /tmp/nodes-check.json 2>/dev/null | cut -d' ' -f1 || md5 -q /tmp/nodes-check.json)
if [ "$local_md5" != "$remote_md5" ]; then
    echo "回读内容和本地不一致（$local_md5 vs $remote_md5）" >&2
    exit 1
fi
echo "  镜像 OK（$(wc -c < /tmp/nodes-check.json) 字节）"

echo
echo "镜像已生效。GitHub 那几条（akams 镜像 + raw）要 git push 之后才更新："
echo "  git add $FILE && git commit -m '免费节点池更新' && git push"
