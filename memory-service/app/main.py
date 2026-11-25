"""本地 mem0 服务。

对外暴露的就是 mem0 OSS 的 REST 契约（与 seeky-ai-runtime 的 Mem0Service 一一对应）：
  POST   /memories              写入（messages + user_id/run_id/agent_id + metadata）
  GET    /memories              列举（user_id/agent_id/run_id/top_k）
  GET    /memories/{id}         单条
  POST   /search                语义检索（query + filters + top_k）
  PUT    /memories/{id}         改文本/元数据
  DELETE /memories/{id}         删
  GET    /memories/{id}/history 变更史
  GET    /health                就绪探针

两个刻意的偏差，都写在 docs/BACKEND-PLAN.md §7.1：
  1. 向量化用 fastembed 本地 ONNX —— 因为火山 Agent Plan 不提供 embedding 模型。
     向量存储可选：`MEM0_VECTOR_STORE=faiss`（本地，文件）或 `pgvector`（服务器，postgres 扩展）。
  2. 写入强制 infer=false —— 容器里没有 LLM，infer=true 会真的去调 OpenAI 然后失败；
     记忆的抽取由 Java 侧的 prompt 做，mem0 只负责存与检索。
"""

import logging
import os
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from mem0 import Memory

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("mem0-service")

# --------------------------------------------------------------------------- 配置

API_KEY = os.getenv("MEM0_API_KEY", "").strip()
AGENT_ID = os.getenv("MEM0_AGENT_ID", "xiaoman").strip()
DATA_DIR = os.getenv("MEM0_DATA_DIR", "/data").strip()
COLLECTION = os.getenv("MEM0_COLLECTION", "memories").strip()
EMBED_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5").strip()
# bge-small-zh-v1.5 实测输出 512 维；维度写错 faiss 会在写入时直接报错，所以从环境变量兜住
EMBED_DIMS = int(os.getenv("EMBEDDING_DIMS", "512"))
DEFAULT_TOP_K = int(os.getenv("MEM0_SEARCH_LIMIT", "8"))

# 向量库后端：faiss（本地单机、纯文件）或 pgvector（postgres，多实例共享）
VECTOR_STORE = os.getenv("MEM0_VECTOR_STORE", "faiss").strip().lower()
PG_HOST = os.getenv("PG_HOST", "postgres").strip()
PG_PORT = int(os.getenv("PG_PORT", "5432"))
PG_USER = os.getenv("PG_USER", "mem0").strip()
PG_PASSWORD = os.getenv("PG_PASSWORD", "").strip()
PG_DB = os.getenv("PG_DB", "mem0").strip()


def _vector_store_config() -> Dict[str, Any]:
    """faiss 与 pgvector 的 config 字段完全不同，这里按后端分别组装。

    pgvector 的键名必须和 mem0 `PGVector.__init__` 的形参一致（dbname/user/password/host/port），
    写错不会报「配置无效」，只会在第一次建表时才炸。
    """
    if VECTOR_STORE == "pgvector":
        return {
            "provider": "pgvector",
            "config": {
                "collection_name": COLLECTION,
                "embedding_model_dims": EMBED_DIMS,
                "dbname": PG_DB,
                "user": PG_USER,
                "password": PG_PASSWORD,
                "host": PG_HOST,
                "port": PG_PORT,
            },
        }
    return {
        "provider": "faiss",
        "config": {
            "collection_name": COLLECTION,
            "path": os.path.join(DATA_DIR, "faiss"),
            "embedding_model_dims": EMBED_DIMS,
        },
    }


def _build_memory() -> Memory:
    os.makedirs(DATA_DIR, exist_ok=True)
    config = {
        "vector_store": _vector_store_config(),
        "embedder": {
            "provider": "fastembed",
            "config": {"model": EMBED_MODEL, "embedding_dims": EMBED_DIMS},
        },
        # 不会被调用（写入一律 infer=false），但 MemoryConfig 要求这一项存在。
        # 给一个指向本机不可达端口的假配置：万一有人真的传了 infer=true 且绕过下面的强制，
        # 也只会立刻超时失败，而不是把对话内容发到某个外部服务。
        "llm": {
            "provider": "openai",
            "config": {"api_key": "unused-by-design", "model": "unused", "openai_base_url": "http://127.0.0.1:1/v1"},
        },
        "history_db_path": os.path.join(DATA_DIR, "history.db"),
    }
    return Memory.from_config(config)


memory = _build_memory()
log.info(
    "mem0 就绪: vector_store=%s collection=%s dims=%s embedder=%s data=%s pg=%s:%s/%s",
    VECTOR_STORE, COLLECTION, EMBED_DIMS, EMBED_MODEL, DATA_DIR,
    PG_HOST if VECTOR_STORE == "pgvector" else "-",
    PG_PORT if VECTOR_STORE == "pgvector" else "-",
    PG_DB if VECTOR_STORE == "pgvector" else "-",
)

app = FastAPI(title="Mem0 Local Service", version="1.0.0")


# --------------------------------------------------------------------------- 鉴权

def require_auth(x_api_key: Optional[str]) -> None:
    """配了 Key 就校验；留空表示本机不校验（只适合本地演示）。"""
    if not API_KEY:
        return
    if (x_api_key or "").strip() != API_KEY:
        raise HTTPException(status_code=401, detail="invalid api key")


# --------------------------------------------------------------------------- 模型

class Message(BaseModel):
    role: str
    content: str


class MemoryCreate(BaseModel):
    messages: List[Message]
    user_id: Optional[str] = None
    agent_id: Optional[str] = None
    run_id: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None
    infer: Optional[bool] = None
    expiration_date: Optional[str] = None


class SearchRequest(BaseModel):
    query: str
    # user_id / agent_id / run_id 在 mem0 新版里已废弃，但 ai-runtime 仍在顶层传，这里兼容
    user_id: Optional[str] = None
    agent_id: Optional[str] = None
    run_id: Optional[str] = None
    filters: Optional[Dict[str, Any]] = None
    top_k: Optional[int] = None
    limit: Optional[int] = None
    threshold: Optional[float] = None
    explain: Optional[bool] = None


class MemoryUpdate(BaseModel):
    text: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None


# --------------------------------------------------------------------------- 工具

def merge_filters(user_id, agent_id, run_id, extra) -> Dict[str, Any]:
    """顶层身份字段合并进 filters。

    为什么必须合并：mem0 2.x 的 search/get_all 只认 filters，顶层 user_id 会被静默忽略，
    结果是「查到了别人的记忆」或「什么都没查到」——两者都不会报错，所以这里显式兜住。
    """
    merged: Dict[str, Any] = dict(extra or {})
    if user_id:
        merged.setdefault("user_id", user_id)
    if agent_id:
        merged.setdefault("agent_id", agent_id)
    if run_id:
        merged.setdefault("run_id", run_id)
    return merged


def pick_top_k(top_k, limit, fallback=DEFAULT_TOP_K) -> int:
    """同时接受 top_k（mem0 新版）与 limit（旧版），夹在 1..1000。"""
    value = top_k if top_k is not None else limit
    if not value:
        value = fallback
    return max(1, min(int(value), 1000))


def fail(ex: Exception) -> HTTPException:
    message = str(ex) or ex.__class__.__name__
    if "not found" in message.lower():
        return HTTPException(status_code=404, detail=message)
    log.warning("mem0 调用失败: %s", message)
    return HTTPException(status_code=500, detail=message)


# --------------------------------------------------------------------------- 路由

@app.get("/health")
def health():
    return {
        "status": "ok",
        "ready": True,
        "agent_id": AGENT_ID,
        "collection": COLLECTION,
        "vector_store": VECTOR_STORE,
        "postgres": (
            {"host": PG_HOST, "port": PG_PORT, "db": PG_DB, "user": PG_USER}
            if VECTOR_STORE == "pgvector" else None
        ),
        "embedding": {"provider": "fastembed", "model": EMBED_MODEL, "dims": EMBED_DIMS},
        "data_dir": DATA_DIR,
        "auth": bool(API_KEY),
    }


@app.post("/memories")
def add_memory(body: MemoryCreate, x_api_key: Optional[str] = Header(default=None)):
    require_auth(x_api_key)
    if not (body.user_id or body.agent_id or body.run_id):
        raise HTTPException(status_code=400, detail="user_id / agent_id / run_id 至少需要一个")
    if body.infer:
        # 见文件头：容器内没有 LLM，硬降级而不是让调用方收到一个 500
        log.warning("忽略 infer=true（本地 mem0 不接 LLM，抽取由上游完成）")

    try:
        result = memory.add(
            [m.model_dump() for m in body.messages],
            user_id=body.user_id,
            agent_id=body.agent_id or AGENT_ID,
            run_id=body.run_id,
            metadata=body.metadata,
            infer=False,
            expiration_date=body.expiration_date,
        )
        return JSONResponse(jsonable_encoder(result))
    except Exception as ex:
        raise fail(ex) from ex


@app.get("/memories")
def list_memories(
    user_id: Optional[str] = Query(default=None),
    agent_id: Optional[str] = Query(default=None),
    run_id: Optional[str] = Query(default=None),
    top_k: Optional[int] = Query(default=None),
    limit: Optional[int] = Query(default=None),
    x_api_key: Optional[str] = Header(default=None),
):
    require_auth(x_api_key)
    if not user_id:
        raise HTTPException(status_code=400, detail="user_id 必填")
    try:
        result = memory.get_all(
            filters=merge_filters(user_id, agent_id or AGENT_ID, run_id, None),
            top_k=pick_top_k(top_k, limit, 1000),
        )
        return JSONResponse(jsonable_encoder(result))
    except Exception as ex:
        raise fail(ex) from ex


@app.get("/memories/{memory_id}")
def get_memory(memory_id: str, x_api_key: Optional[str] = Header(default=None)):
    require_auth(x_api_key)
    try:
        return JSONResponse(jsonable_encoder(memory.get(memory_id)))
    except Exception as ex:
        raise fail(ex) from ex


@app.post("/search")
def search(body: SearchRequest, x_api_key: Optional[str] = Header(default=None)):
    require_auth(x_api_key)
    if not body.query or not body.query.strip():
        raise HTTPException(status_code=400, detail="query 不能为空")
    filters = merge_filters(body.user_id, body.agent_id or AGENT_ID, body.run_id, body.filters)
    if not filters.get("user_id"):
        raise HTTPException(status_code=400, detail="缺少 user_id（顶层或 filters 内）")
    try:
        kwargs: Dict[str, Any] = {
            "filters": filters,
            "top_k": pick_top_k(body.top_k, body.limit),
        }
        if body.threshold is not None:
            kwargs["threshold"] = body.threshold
        if body.explain is not None:
            kwargs["explain"] = body.explain
        return JSONResponse(jsonable_encoder(memory.search(body.query, **kwargs)))
    except Exception as ex:
        raise fail(ex) from ex


@app.put("/memories/{memory_id}")
def update_memory(memory_id: str, body: MemoryUpdate, x_api_key: Optional[str] = Header(default=None)):
    require_auth(x_api_key)
    if body.text is None and body.metadata is None:
        raise HTTPException(status_code=400, detail="text 与 metadata 至少要有一个")
    try:
        result = memory.update(memory_id, text=body.text, metadata=body.metadata)
        return JSONResponse(jsonable_encoder(result))
    except Exception as ex:
        raise fail(ex) from ex


@app.delete("/memories/{memory_id}")
def delete_memory(memory_id: str, x_api_key: Optional[str] = Header(default=None)):
    require_auth(x_api_key)
    try:
        memory.delete(memory_id)
        # 2.x 的 delete 返回 None，这里固定回一个可判定的结果，调用方不用猜
        return {"deleted": True, "id": memory_id}
    except Exception as ex:
        raise fail(ex) from ex


@app.get("/memories/{memory_id}/history")
def memory_history(memory_id: str, x_api_key: Optional[str] = Header(default=None)):
    require_auth(x_api_key)
    try:
        return JSONResponse(jsonable_encoder(memory.history(memory_id)))
    except Exception as ex:
        raise fail(ex) from ex
