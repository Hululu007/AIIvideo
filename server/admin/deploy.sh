#!/usr/bin/env bash
#
# 部署运维后台到 relay。
#
#   ./deploy.sh            打包 web/ → relay:/srv/admin，重建容器
#   ./deploy.sh --no-build 只同步代码，不重建（改模板/静态文件时够用）
#
# 前置：VPS 上 /srv/admin/.env 已经存在（首次从 .env.example 复制并填好）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE="${ADMIN_REMOTE:-relay}"
REMOTE_DIR="${ADMIN_REMOTE_DIR:-/srv/admin}"
BUILD=1
[ "${1:-}" = "--no-build" ] && BUILD=0

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

say "同步代码到 $REMOTE:$REMOTE_DIR"
ssh "$REMOTE" "mkdir -p '$REMOTE_DIR'"
rsync -az --delete \
  --exclude 'data/' --exclude '.env' --exclude '__pycache__/' \
  "$HERE/web" "$HERE/docker-compose.yml" "$HERE/README.md" \
  "$REMOTE:$REMOTE_DIR/"

if [ "$BUILD" = "1" ]; then
  say "重建容器"
  ssh "$REMOTE" "cd '$REMOTE_DIR' && test -f .env || { echo '缺少 $REMOTE_DIR/.env，先 cp .env.example .env 填好'; exit 1; } && docker compose up -d --build"
  say "健康检查"
  ssh "$REMOTE" "curl -fsS --max-time 5 http://127.0.0.1:8091/healthz" && echo
fi
say "完成"
