#!/bin/bash
# dev-up.sh로 띄운 프로세스와 Docker 컨테이너를 끈다. 데이터 볼륨은 남긴다
cd "$(dirname "$0")/.."
for f in .run/*.pid; do
  [ -f "$f" ] || continue
  pid=$(cat "$f"); kill "$pid" 2>/dev/null && echo "$(basename "$f" .pid) 종료"; rm -f "$f"
done
pkill -f 'api-0.1.0.jar|worker-0.1.0.jar|external-fetcher-0.1.0.jar' 2>/dev/null || true
pkill -f 'vite.*15173|gyeol/web.*vite' 2>/dev/null || true
pkill -f 'uvicorn app:app --port 18084' 2>/dev/null || true
[ "${KEEP_DOCKER:-0}" = 1 ] || docker compose -f local/compose.yaml stop
