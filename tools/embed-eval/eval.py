"""임베딩 모델 비교 (docs/l3-design.md 7장). 맥북 CPU에서 정확도·속도·메모리를 잰다.
  uv run eval.py intfloat/multilingual-e5-small sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
"""
import json, os, resource, sys, time
from pathlib import Path

import numpy as np
from sentence_transformers import SentenceTransformer

data = json.loads((Path(__file__).parent / "samples.json").read_text())
docs, labels = [], []
for g, sents in data["groups"].items():
    docs += sents; labels += [g] * len(sents)
queries = data["queries"]
hangul = lambda s: any("가" <= c <= "힣" for c in s)

def run(name: str):
    e5 = "e5" in name  # e5 계열은 질의·문서 앞에 접두어가 필요하다
    t0 = time.time(); model = SentenceTransformer(name, device="cpu"); load_s = time.time() - t0
    P = (lambda xs: [f"passage: {x}" for x in xs]) if e5 else (lambda xs: xs)
    Q = (lambda xs: [f"query: {x}" for x in xs]) if e5 else (lambda xs: xs)
    D = model.encode(P(docs), normalize_embeddings=True)
    # 1) 곡 설명끼리: 자기 자신을 뺀 가장 가까운 문장이 같은 묶음인가 (추천 후보 찾기)
    S = D @ D.T; np.fill_diagonal(S, -1)
    nn = S.argmax(1); doc_top1 = np.mean([labels[i] == labels[j] for i, j in enumerate(nn)])
    # e5는 비슷한 문장끼리 비교할 때 양쪽에 "query: " 접두어를 권장한다 (대칭 작업)
    Dsym = model.encode(Q(docs), normalize_embeddings=True) if e5 else D
    Ssym = Dsym @ Dsym.T; np.fill_diagonal(Ssym, -1)
    doc_top1_sym = np.mean([labels[i] == labels[j] for i, j in enumerate(Ssym.argmax(1))])
    # 같은 언어끼리만 (실제 곡 설명은 LLM이 한 언어로 만든다)
    lang = lambda s: "ko" if hangul(s) else ("ja" if any("\u3040" <= c <= "\u30ff" or "\u4e00" <= c <= "\u9fff" for c in s) else "en")
    L = [lang(d) for d in docs]
    S3 = Ssym.copy()
    for i in range(len(docs)):
        for j in range(len(docs)):
            if L[i] != L[j]: S3[i, j] = -1
    same_lang_top1 = np.mean([labels[i] == labels[S3[i].argmax()] for i in range(len(docs))])
    # 다른 언어 문장끼리만 봤을 때 (한국어 설명 ↔ 영어·일본어 설명)
    cross = [i for i in range(len(docs)) if hangul(docs[i])]
    S2 = S.copy()
    for i in cross: S2[i, [j for j in range(len(docs)) if hangul(docs[j])]] = -1
    cross_top1 = np.mean([labels[i] == labels[S2[i].argmax()] for i in cross])
    # 2) 짧은 요청 → 곡 설명: 1위 정확도와 평균 역순위(MRR, 정답 묶음 문장 중 가장 높은 순위)
    Qv = model.encode(Q([q["q"] for q in queries]), normalize_embeddings=True)
    R = Qv @ D.T
    top1, rr = [], []
    for k, q in enumerate(queries):
        order = np.argsort(-R[k]); top1.append(labels[order[0]] == q["group"])
        rank = next(r for r, j in enumerate(order, 1) if labels[j] == q["group"]); rr.append(1 / rank)
    # 3) 속도: 100문장 묶음, 1문장
    batch = (docs * 3)[:100]; model.encode(P(batch[:8]))
    t0 = time.time(); model.encode(P(batch), batch_size=32); b100 = time.time() - t0
    t0 = time.time(); [model.encode(P([d])) for d in docs[:20]]; one = (time.time() - t0) / 20
    rss_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024 / 1024  # macOS는 바이트
    return dict(model=name, dim=D.shape[1], doc_top1=doc_top1, doc_top1_sym=doc_top1_sym, same_lang_top1=same_lang_top1, cross_top1=cross_top1, q_top1=np.mean(top1), q_mrr=np.mean(rr),
                b100_s=b100, one_ms=one * 1000, load_s=load_s, rss_mb=rss_mb)

for name in sys.argv[1:]:
    r = run(name)
    print(json.dumps({k: (round(v, 3) if isinstance(v, float) else v) for k, v in r.items()}, ensure_ascii=False), flush=True)
