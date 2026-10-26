#!/bin/bash
# 로컬 서비스 상태
for s in "PostgreSQL 15432" "LocalStack 4566" "api 18080" "fetcher 18081" "worker 18082" "web 15173"; do
  set -- $s
  if lsof -iTCP:"$2" -sTCP:LISTEN -nP >/dev/null 2>&1; then echo "✓ $1 ($2)"; else echo "✗ $1 ($2)"; fi
done
