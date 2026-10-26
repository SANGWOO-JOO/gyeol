#!/bin/bash
# 테스트 전체: 백엔드(단위 + Testcontainers 통합, Docker 필요) + 웹(Vitest). 끝에 모듈별 개수 요약
set -uo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Users/sangwoo/Library/Java/JavaVirtualMachines/corretto-21.0.4/Contents/Home}"
docker info >/dev/null 2>&1 || { echo "Docker가 꺼져 있습니다 (통합 테스트에 필요). Docker Desktop을 켜고 다시 실행하세요."; exit 1; }

started=$(date +%s)
./gradlew test --continue -q; back=$?
(cd web && npx vitest run --reporter=dot); web=$?

echo
echo "== 결과 =="
for m in api worker external-fetcher core; do
  d="backend/$m/build/test-results/test"
  [ -d "$d" ] || continue
  python3 - "$m" "$d" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
m, d = sys.argv[1], sys.argv[2]
t = f = s = 0
for x in glob.glob(f"{d}/*.xml"):
    r = ET.parse(x).getroot()
    t += int(r.get("tests")); f += int(r.get("failures")) + int(r.get("errors")); s += int(r.get("skipped"))
print(f"{m:17} {t:3}개 {'통과' if f == 0 else f'실패 {f}'}{f', 건너뜀 {s}' if s else ''}")
PY
done
echo "web               $([ $web = 0 ] && echo 통과 || echo 실패)"
echo "걸린 시간 $(( $(date +%s) - started ))초"
[ $back = 0 ] && [ $web = 0 ]
