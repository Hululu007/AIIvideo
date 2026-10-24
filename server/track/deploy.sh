#!/usr/bin/env bash
#
# 部署埋点上报服务到 relay。
#
#   ./deploy.sh            打包 web/ → relay:/srv/track，重建容器
#   ./deploy.sh --no-build 只同步代码，不重建。Dockerfile 是 COPY app，
#                          代码和镜像是一体的 —— 不重建等于没发。
#
# 前置：~/.ssh/config 里有 relay，key 已通。
# .env 不存在时会现场生成 POSTGRES_PASSWORD / TRACK_SECRET；已有的绝不覆盖
# （覆盖 POSTGRES_PASSWORD 会让旧容器连不上自己的库，覆盖 TRACK_SECRET 会让
#   线上 App 全部 401）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE="${TRACK_REMOTE:-relay}"
REMOTE_DIR="${TRACK_REMOTE_DIR:-/srv/track}"
BUILD=1
[ "${1:-}" = "--no-build" ] && BUILD=0

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

say "准备 $REMOTE:$REMOTE_DIR/.env"
ssh "$REMOTE" bash -s <<REMOTE_SCRIPT
set -euo pipefail
mkdir -p '$REMOTE_DIR'
cd '$REMOTE_DIR'
if [ ! -f .env ]; then
  umask 077
  {
    echo "POSTGRES_PASSWORD=\$(openssl rand -hex 32)"
    echo "TRACK_SECRET=\$(openssl rand -hex 32)"
    echo "RETENTION_DAYS=90"
    echo "AGG_TZ=Asia/Shanghai"
    echo "RATE_LIMIT_PER_MINUTE=60"
    echo "MAX_EVENTS=200"
  } > .env
  chmod 600 .env
  echo "  已生成新的 .env（两个密钥都是 openssl rand -hex 32）"
else
  echo "  .env 已存在，保留原值"
fi
# 老部署没有 STATS_TOKEN（后台数据页要用）。缺就补一条，已有的不动。
if ! grep -q '^STATS_TOKEN=' .env; then
  echo "STATS_TOKEN=\$(openssl rand -hex 32)" >> .env
  echo "  给 .env 补了一条 STATS_TOKEN（后台数据页读 /stats/* 用）"
fi
REMOTE_SCRIPT

say "同步代码到 $REMOTE:$REMOTE_DIR"
rsync -az --delete \
  --exclude 'pgdata/' --exclude 'data/' --exclude '.env' \
  --exclude '__pycache__/' --exclude '.venv/' \
  "$HERE/web" "$HERE/docker-compose.yml" "$HERE/README.md" \
  "$REMOTE:$REMOTE_DIR/"

if [ "$BUILD" = "1" ]; then
  say "重建容器"
  ssh "$REMOTE" "cd '$REMOTE_DIR' && test -f .env || { echo '缺少 $REMOTE_DIR/.env'; exit 1; } && docker compose up -d --build"
  say "健康检查"
  ssh "$REMOTE" "for i in \$(seq 1 20); do curl -fsS --max-time 3 http://127.0.0.1:8092/healthz && break || sleep 2; done" && echo
fi

say "完成"
cat <<EOF

如果这是第一次部署，把新密钥取回来：
  ssh $REMOTE "grep TRACK_SECRET $REMOTE_DIR/.env"

那个值要写进 App 侧的签名配置，也要追加到本机 .secrets/server.md 的「埋点上报」小节。
Caddy 那一段（handle_path /t/*）是手改的，不在这个脚本里 —— 见 README「Caddy」。
EOF
