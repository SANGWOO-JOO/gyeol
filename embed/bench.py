"""ONNX 판 정확도·속도·메모리 측정 (L3-3). L3-0과 같은 평가 세트(tools/embed-eval/samples.json)
  uv run bench.py onnx/model.onnx
  uv run bench.py onnx/model_qint8_avx512_vnni.onnx
같은 문장 벡터를 PyTorch 판과 비교하려면 --compare 경로(tools/embed-eval에서 만든 .npy)를 준다.
"""
import json, os, resource, sys, time
from pathlib import Path

import numpy as np

from app import Embedder, MODEL_ID

model_file = sys.argv[1]
os.environ["EMBED_MODEL_FILE"] = model_file
from huggingface_hub import snapshot_download

t0 = time.time()
d = snapshot_download(MODEL_ID, allow_patterns=["onnx/tokenizer.json", "onnx/config.json", model_file])
e = Embedder(d, model_file)
load_s = time.time() - t0

data = json.loads((Path(__file__).parent.parent / "tools/embed-eval/samples.json").read_text())
docs, labels = [], []
for g, s in data["groups"].items():
    docs += s; labels += [g] * len(s)
hangul = lambda s: any("가" <= c <= "힣" for c in s)

D = e.embed(docs, "query")  # 곡 설명끼리는 양쪽 "query:" (L3-0)
S = D @ D.T; np.fill_diagonal(S, -1)
doc_top1 = np.mean([labels[i] == labels[j] for i, j in enumerate(S.argmax(1))])
P = e.embed(docs, "passage")
Q = e.embed([q["q"] for q in data["queries"]], "query")
R = Q @ P.T
q_top1 = np.mean([labels[R[k].argmax()] == q["group"] for k, q in enumerate(data["queries"])])

batch = (docs * 3)[:100]
e.embed(batch[:8], "query")
t0 = time.time(); e.embed(batch, "query"); b100 = time.time() - t0
t0 = time.time(); [e.embed([x], "query") for x in docs[:20]]; one = (time.time() - t0) / 20
rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024 / 1024
size = os.path.getsize(os.path.join(d, model_file)) / 1024 / 1024
out = dict(model=model_file, file_mb=round(size), doc_top1=round(doc_top1, 3), q_top1=round(q_top1, 3), b100_s=round(b100, 3), one_ms=round(one * 1000, 1), load_s=round(load_s, 1), rss_mb=round(rss))
if "--compare" in sys.argv:
    ref = np.load(sys.argv[sys.argv.index("--compare") + 1])
    cos = (D * ref).sum(1)
    out["cos_vs_torch_min"] = round(float(cos.min()), 4)
print(json.dumps(out, ensure_ascii=False))
