FROM python:3.12-slim

# 不写 .pyc、日志不缓冲（docker logs 要能实时看到）
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 不用 root 跑。容器里只有一个进程，没有需要提权的场景。
RUN useradd -m -u 10001 track

# --chown + chmod：rsync 过来的文件可能是 600（root 所有），非 root 用户就读不到。
# 镜像里不依赖宿主机的权限位，这条别删。
COPY --chown=track:track app ./app
RUN chmod -R a+rX app

USER track

EXPOSE 8092
# 容器内监听 0.0.0.0：compose 只把 127.0.0.1:8092 发布到宿主机，
# 外面统一走 Caddy（host 网络，够得着那个回环端口）。
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8092", "--no-access-log"]
