# embed 서비스 (L3-3): Python + ONNX Runtime, PyTorch 없음. 모델 파일을 이미지에 넣어 파드가 뜰 때 내려받지 않게 한다
#   docker build -f deploy/docker/embed.Dockerfile -t gyeol/embed:dev .
FROM python:3.13-slim AS model
RUN pip install --no-cache-dir "huggingface-hub>=0.26"
RUN python -c "from huggingface_hub import snapshot_download; snapshot_download('intfloat/multilingual-e5-small', allow_patterns=['onnx/model.onnx','onnx/tokenizer.json','onnx/config.json'], local_dir='/models/e5')"

FROM python:3.13-slim
RUN pip install --no-cache-dir "fastapi>=0.115" "uvicorn>=0.32" "onnxruntime>=1.20" "tokenizers>=0.20" "numpy>=2" \
    && useradd -u 1000 -m app
WORKDIR /app
COPY --from=model /models /models
COPY embed/app.py .
USER 1000:1000
ENV EMBED_MODEL_DIR=/models/e5 EMBED_THREADS=2 PYTHONUNBUFFERED=1
EXPOSE 18084
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "18084"]
