FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app ./app

EXPOSE 8091
# 只监听回环：对外统一走 Caddy（它用 host 网络，够得着这个端口）。
CMD ["uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8091", "--proxy-headers"]
