"""곡 설명 임베딩 서비스 (docs/l3-design.md L3-3).

intfloat/multilingual-e5-small을 ONNX Runtime으로 돌린다 (PyTorch 없음 → 메모리·이미지 크기 절약).
e5는 접두어가 필요하다: 질의·곡 설명끼리 비교는 "query: ", 검색 대상 문서는 "passage: " (L3-0 측정).

환경 변수
  EMBED_MODEL_FILE   onnx/model.onnx (fp32, 기본) | onnx/model_qint8_avx512_vnni.onnx (int8)
  EMBED_MODEL_DIR    모델 폴더 (컨테이너는 이미지에 포함). 없으면 Hugging Face에서 받는다
  EMBED_THREADS      ONNX Runtime 스레드 수 (기본: CPU 수)
  EMBED_GRAPH_OPT    basic(기본, 메모리 적음) | all
"""
import os
import time
from contextlib import asynccontextmanager
from typing import Literal

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from tokenizers import Tokenizer

MODEL_ID = "intfloat/multilingual-e5-small"
MAX_TOKENS = 256
MAX_TEXTS = 64


class Embedder:
    def __init__(self, model_dir: str, model_file: str, threads: int | None = None):
        opts = ort.SessionOptions()
        # 미리 크게 잡는 메모리 풀(arena)을 끈다: 처음 측정에서 최대 메모리가 PyTorch보다 컸다 (1,247MB)
        if os.environ.get("EMBED_MEM_ARENA", "0") != "1":
            opts.enable_cpu_mem_arena = False
            opts.enable_mem_pattern = False
        # 그래프 최적화는 기본 단계만: 전부 켜면 시작할 때 원본·최적화본을 함께 올려 최대 메모리가 1,047MB → 기본만 805MB (정확도 같음)
        if os.environ.get("EMBED_GRAPH_OPT", "basic") == "basic":
            opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
        if threads:
            opts.intra_op_num_threads = threads
        self.session = ort.InferenceSession(os.path.join(model_dir, model_file), opts, providers=["CPUExecutionProvider"])
        self.inputs = {i.name for i in self.session.get_inputs()}
        self.tokenizer = Tokenizer.from_file(os.path.join(model_dir, "onnx", "tokenizer.json"))
        self.tokenizer.enable_truncation(MAX_TOKENS)
        self.tokenizer.enable_padding(pad_id=self.tokenizer.token_to_id("<pad>") or 1, pad_token="<pad>")
        self.name = f"{MODEL_ID}:{os.path.basename(model_file)}"

    def embed(self, texts: list[str], kind: str) -> np.ndarray:
        enc = self.tokenizer.encode_batch([f"{kind}: {t}" for t in texts])
        ids = np.array([e.ids for e in enc], dtype=np.int64)
        mask = np.array([e.attention_mask for e in enc], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in self.inputs:
            feed["token_type_ids"] = np.zeros_like(ids)
        hidden = self.session.run(None, feed)[0]  # (문장, 토큰, 384)
        # 평균 풀링(패딩 제외) 후 길이 1로 정규화 (e5 권장 방식)
        m = mask[..., None].astype(np.float32)
        pooled = (hidden * m).sum(1) / np.clip(m.sum(1), 1e-9, None)
        return pooled / np.linalg.norm(pooled, axis=1, keepdims=True)


def model_dir() -> str:
    d = os.environ.get("EMBED_MODEL_DIR")
    if d:
        return d
    from huggingface_hub import snapshot_download
    return snapshot_download(MODEL_ID, allow_patterns=["onnx/tokenizer.json", "onnx/config.json", os.environ.get("EMBED_MODEL_FILE", "onnx/model.onnx")])


state: dict = {}


@asynccontextmanager
async def lifespan(_: FastAPI):
    started = time.time()
    state["embedder"] = Embedder(model_dir(), os.environ.get("EMBED_MODEL_FILE", "onnx/model.onnx"), int(os.environ.get("EMBED_THREADS", "0")) or None)
    print(f"모델 준비: {state['embedder'].name}, {time.time() - started:.1f}초", flush=True)
    yield
    state.clear()


app = FastAPI(title="gyeol-embed", lifespan=lifespan)


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1)
    kind: Literal["query", "passage"] = "query"


@app.post("/embed")
def embed(req: EmbedRequest):
    if len(req.texts) > MAX_TEXTS:
        raise HTTPException(400, f"texts는 {MAX_TEXTS}개 이하여야 합니다")
    if any(not t.strip() for t in req.texts):
        raise HTTPException(400, "빈 문장이 있습니다")
    e = state.get("embedder")
    if e is None:
        raise HTTPException(503, "모델을 준비하는 중입니다")
    v = e.embed(req.texts, req.kind)
    return {"vectors": v.round(6).tolist(), "dim": int(v.shape[1]), "model": e.name}


@app.get("/healthz")
def healthz():
    return {"status": "UP"}


@app.get("/readyz")
def readyz():
    if "embedder" not in state:
        raise HTTPException(503, "모델을 준비하는 중입니다")
    return {"status": "UP", "model": state["embedder"].name}
