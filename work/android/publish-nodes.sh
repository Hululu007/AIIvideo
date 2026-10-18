#!/usr/bin/env bash
#
# 发布免费节点池。
#
#   ./publish-nodes.sh                只校验
#   NODES_PUSH=1 ./publish-nodes.sh   校验后自动 commit + push
#
# ── 现在只走 GitHub ──
# 以前这个脚本还会 scp 到自建服务器当镜像。去掉了：节点池是纯静态文件，
# 没有任何理由让它受某台机器欠费、被封、磁盘满的影响。App 的三条候选
# （两个 akams 镜像 + raw）全部指向 GitHub 仓库里的这一份。
#
# 所以「发布」= git push。这个脚本剩下的价值在**推送之前的校验**：
# 让一份坏数据在源头就被拦住，而不是等十万台手机各自拒绝一次。
#
# 可覆盖的环境变量：
#   NODES_FILE=...   默认 app/src/main/assets/free-nodes.json
#   NODES_PUSH=1     校验通过后自动提交并推送
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FILE="${NODES_FILE:-$HERE/app/src/main/assets/free-nodes.json}"
PUSH="${NODES_PUSH:-0}"

[ -f "$FILE" ] || { echo "找不到节点文件：$FILE" >&2; exit 1; }

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
    key = m.group(1).lower() if m else '(认不出)'
    schemes[key] = schemes.get(key, 0) + 1

unsupported = sum(v for k, v in schemes.items() if k in ('hysteria2', 'tuic', 'ssr'))
print(f"  updated = {root.get('updated', '(缺失)')}")
print(f"  链接    = {len(links)} 条")
print(f"  协议    = {', '.join(f'{k}:{v}' for k, v in sorted(schemes.items()))}")
if unsupported:
    print(f"  注意：{unsupported} 条是核心不支持的协议（hysteria2 / tuic / ssr），客户端会静默跳过")
if len(links) - unsupported < 3:
    sys.exit("去掉不支持的协议后不足 3 条 —— 客户端会拒绝这份数据")
PY

if [ "$PUSH" != "1" ]; then
    echo
    echo "校验通过。加 NODES_PUSH=1 直接 commit + push。"
    exit 0
fi

UPDATED=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('updated','未知日期'))" "$FILE")

echo "提交并推送"
cd "$REPO"
git add "$FILE"
git commit -q -m "免费节点池更新（$UPDATED）" || { echo "没有变化可提交。"; exit 0; }
git push origin main

echo
echo "已推送。三个候选（两个 akams 镜像 + raw）大约 5 分钟收敛；"
echo "客户端最长 6 小时会自己检查一次，也可以在设置页点「检查更新」立即拉。"
