#!/bin/bash
# k3d(로컬 쿠버네티스)에 gyeol 전체를 올린다. DB·LocalStack은 클러스터 밖 compose를 쓴다.
#   scripts/k3d-up.sh            # 클러스터가 없으면 만들고, 빌드 → 이미지 → 배포
#   SKIP_BUILD=1 scripts/k3d-up.sh   # 빌드 생략 (이미지만 다시 넣고 배포)
# 접속: http://localhost:18000   끄기: scripts/k3d-down.sh
set -euo pipefail
cd "$(dirname "$0")/.."
CLUSTER=gyeol NS=gyeol PORT=18000
TAG=$(date +%Y%m%d%H%M%S)
export JAVA_HOME="${JAVA_HOME:-/Users/sangwoo/Library/Java/JavaVirtualMachines/corretto-21.0.4/Contents/Home}"
t0=$(date +%s); lap() { echo "  ($(( $(date +%s) - t0 ))초)"; }

docker info >/dev/null 2>&1 || { echo "Docker가 꺼져 있습니다. scripts/dev-up.sh 또는 Docker Desktop을 먼저 켜세요."; exit 1; }
docker compose -f local/compose.yaml up -d >/dev/null

echo "1) 빌드"
if [ "${SKIP_BUILD:-0}" != 1 ]; then
  ./gradlew -q build -x test
  [ -d web/node_modules ] || (cd web && npm install --silent)
  (cd web && npm run -s build >/dev/null)
fi
lap

echo "2) 이미지 (태그 $TAG)"
for m in api worker external-fetcher; do
  docker build -q -f deploy/docker/backend.Dockerfile --build-arg MODULE=$m -t gyeol/$m:$TAG . >/dev/null
done
docker build -q -f deploy/docker/web.Dockerfile -t gyeol/web:$TAG . >/dev/null
docker build -q -f deploy/docker/embed.Dockerfile -t gyeol/embed:$TAG . >/dev/null
lap

echo "3) 클러스터"
if ! k3d cluster list -o json | grep -q "\"name\":\"$CLUSTER\""; then
  k3d cluster create $CLUSTER --agents 1 -p "$PORT:80@loadbalancer" --wait >/dev/null
  echo "  새로 만듦"
fi
kubectl config use-context k3d-$CLUSTER >/dev/null
# 새 클러스터는 맥북 주소(host.k3d.internal)가 CoreDNS에 조금 늦게 들어간다. 그 전에 뜬 파드는 DB 연결에 실패해 한 번 재시작한다
for _ in $(seq 1 30); do
  kubectl -n kube-system get configmap coredns -o jsonpath='{.data.NodeHosts}' 2>/dev/null | grep -q host.k3d.internal && break
  sleep 2
done
k3d image import -c $CLUSTER gyeol/api:$TAG gyeol/worker:$TAG gyeol/external-fetcher:$TAG gyeol/web:$TAG gyeol/embed:$TAG >/dev/null
lap

echo "4) 비밀 값 (.env에서 읽음, 출력하지 않음)"
kubectl create namespace $NS --dry-run=client -o yaml | kubectl apply -f - >/dev/null
( set -a; [ -f .env ] && . ./.env; set +a
  kubectl -n $NS create secret generic gyeol-secrets \
    --from-literal=DB_PASSWORD="${DB_PASSWORD:-gyeol}" \
    --from-literal=LASTFM_API_KEY="${LASTFM_API_KEY:-}" \
    --from-literal=YOUTUBE_API_KEY="${YOUTUBE_API_KEY:-}" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null )

echo "5) 배포"
helm upgrade --install gyeol deploy/helm/gyeol -n $NS \
  -f deploy/helm/gyeol/values-local.yaml --set image.tag=$TAG \
  --wait --timeout 5m >/dev/null
# 새 클러스터는 Ingress(Traefik)가 배포보다 늦게 준비될 수 있다
kubectl -n kube-system rollout status deploy/traefik --timeout=120s >/dev/null
until curl -sf -m 2 -o /dev/null "http://localhost:$PORT/"; do sleep 1; done
lap
kubectl -n $NS get pods
# 배포마다 새 태그로 이미지를 만들므로, 이번 태그만 남기고 이전 gyeol 이미지는 지운다 (저장 공간)
docker images --format '{{.Repository}}:{{.Tag}}' | grep -E '^gyeol/(api|worker|external-fetcher|web|embed):' | grep -v ":$TAG$" | xargs -r docker rmi >/dev/null 2>&1 || true
echo "접속: http://localhost:$PORT"
