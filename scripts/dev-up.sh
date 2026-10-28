#!/bin/bash
# 로컬 개발 환경 전체 시작: Docker(DB·LocalStack) → 빌드 → api·worker·fetcher → 웹
# 로그와 PID는 .run/ (git 제외). 끄기: scripts/dev-down.sh, 상태: scripts/dev-status.sh
set -euo pipefail
cd "$(dirname "$0")/.."
RUN=.run; mkdir -p "$RUN"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}"
# .env의 키·설정(LASTFM_API_KEY, YOUTUBE_API_KEY, LLM_MODE 등)을 모든 서비스에 넘긴다. 값은 출력하지 않는다
# LLM_MODE=bedrock이면 worker가 맥북의 AWS 자격 증명으로 Bedrock을 부른다 (비용 발생, 기본은 fake)
set -a; [ -f .env ] && . ./.env; set +a
JAVA="$JAVA_HOME/bin/java"

# 1) Docker Desktop이 꺼져 있으면 켜고 기다린다
if ! docker info >/dev/null 2>&1; then
  echo "Docker Desktop 시작 중…"; open -a Docker
  for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 2; done
fi
docker compose -f local/compose.yaml up -d

# 2) 빌드 (테스트는 건너뜀, SKIP_BUILD=1이면 생략)
[ "${SKIP_BUILD:-0}" = 1 ] || ./gradlew -q build -x test

# 3) 백엔드 세 개. 이미 떠 있으면 건너뜀
start() { # 이름 포트 명령…
  local name=$1 port=$2; shift 2
  if lsof -iTCP:"$port" -sTCP:LISTEN -nP >/dev/null 2>&1; then echo "$name($port) 이미 실행 중"; return; fi
  nohup "$@" > "$RUN/$name.log" 2>&1 & echo $! > "$RUN/$name.pid"
  echo "$name($port) 시작, 로그 $RUN/$name.log"
}
SPRING_PROFILES_ACTIVE=local start api 18080 "$JAVA" -jar backend/api/build/libs/api-0.1.0.jar
SPRING_PROFILES_ACTIVE=local start worker 18082 "$JAVA" -jar backend/worker/build/libs/worker-0.1.0.jar
start fetcher 18081 "$JAVA" -jar backend/external-fetcher/build/libs/external-fetcher-0.1.0.jar

# 3-1) embed 서비스 (L3-3, Python). 처음 한 번 의존성 설치, 모델은 Hugging Face 캐시에서
(cd embed && uv sync -q --no-dev --python 3.13)
start embed 18084 uv run --directory embed --no-dev uvicorn app:app --port 18084

# 4) 웹 개발 서버
[ -d web/node_modules ] || (cd web && npm install)
start web 15173 npm --prefix web run dev

# 5) 준비될 때까지 기다림
for _ in $(seq 1 60); do
  ok=1; for p in 18080 18081 18082; do [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:$p/actuator/health)" = 200 ] || ok=0; done
  [ $ok = 1 ] && break; sleep 2
done
"$(dirname "$0")/dev-status.sh"
echo "웹: http://localhost:15173"
