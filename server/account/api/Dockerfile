FROM python:3.12-slim

# 不写 .pyc、日志不缓冲（docker logs 要能实时看到）
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py schema.sql ./

# 不用 root 跑。容器里只有一个进程，没有需要提权的场景。
RUN useradd -m -u 10001 aii
USER aii

EXPOSE 8090
CMD ["uvicorn", "main:APP", "--host", "0.0.0.0", "--port", "8090", "--workers", "2", "--no-access-log"]
