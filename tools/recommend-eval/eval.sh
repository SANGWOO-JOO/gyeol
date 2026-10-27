#!/usr/bin/env bash
# 추천 재정렬 모델 비교 (L3-4). 모델마다 따로 부르고 Bedrock 503이면 30·60·120초 뒤 다시. 호출마다 Bedrock 비용이 든다
# 사용: tools/recommend-eval/eval.sh <세션 id> <모델>...
set -u
SID=$1; shift
OUT=$(dirname "$0")/results; mkdir -p "$OUT"
for m in "$@"; do
  for wait in 0 30 60 120; do
    sleep $wait
    code=$(curl -s -m 600 -X POST localhost:18082/internal/recommend/eval -H 'Content-Type: application/json' \
      -d "{\"sessionId\":$SID,\"models\":[\"$m\"]}" -o "$OUT/s$SID-${m##*.}.json" -w '%{http_code}')
    echo "$m → $code"; [ "$code" = 200 ] && break
  done
done
