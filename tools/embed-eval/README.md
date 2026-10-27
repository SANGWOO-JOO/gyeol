# 임베딩 모델 비교 (L3-0)

곡 설명 문장(`samples.json`, 직접 만든 6묶음 × 한·영·일)으로 정확도·속도·메모리를 잰다. 결과와 선택 근거는 `docs/l3-design.md` 7장.

```bash
cd tools/embed-eval
uv sync --python 3.13          # .venv (git 제외), sentence-transformers·PyTorch 설치
uv run eval.py intfloat/multilingual-e5-small    # 모델은 ~/.cache/huggingface에 받음 (약 470MB)
```

모델마다 따로 실행해야 메모리 값이 섞이지 않는다.
