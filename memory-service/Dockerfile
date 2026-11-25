# 国内源：基础镜像走 1ms 代理（daocloud 只代理元数据，blob 仍回源 cloudfront，会断流）。
# 服务器上 daemon 已配腾讯云加速器，直接拉 docker.io 更快，用 build arg 覆盖即可：
#   docker build --build-arg BASE_IMAGE=python:3.11-slim .
ARG BASE_IMAGE=docker.1ms.run/library/python:3.11-slim
FROM ${BASE_IMAGE}

# pip 走清华源；fastembed 的模型从 hf-mirror 拉（huggingface.co 在国内基本不可用）
ENV PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    PIP_TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn \
    HF_ENDPOINT=https://hf-mirror.com \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 构建期把 embedding 模型下进镜像（~95MB）：运行时不依赖 hf-mirror 能否连上，
# 容器起来就能用。换模型时记得同步改 .env 的 EMBEDDING_MODEL 并重建。
RUN python -c "from fastembed import TextEmbedding; TextEmbedding('BAAI/bge-small-zh-v1.5')"

COPY app ./app

# faiss 索引与 history 库都放这里，compose 挂出来，删容器不丢记忆
VOLUME ["/data"]

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
