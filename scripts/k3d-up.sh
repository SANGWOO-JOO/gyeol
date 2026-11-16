#!/bin/bash
# k3d(로컬 쿠버네티스)에 gyeol 전체를 올린다. 개발 환경(dev-up)과 데이터를 나눈다(11-16):
#   DB는 클러스터 안 PostgreSQL, LocalStack은 k3d 전용 컨테이너(14566). 개발 DB(15432)·LocalStack(4566)은 쓰지 않는다
#   scripts/k3d-up.sh            # 클러스터가 없으면 만들고, 빌드 → 이미지 → 배포
#   SKIP_BUILD=1 scripts/k3d-up.sh   # 빌드 생략 (이미지만 다시 넣고 배포)
# 접속: http://localhost:18000   끄기: scripts/k3d-down.sh
set -euo pipefail
cd "$(dirname "$0")/.."
CLUSTER=gyeol NS=gyeol PORT=18000
TAG=$(date +%Y%m%d%H%M%S)
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}"
t0=$(date +%s); lap() { echo "  ($(( $(date +%s) - t0 ))초)"; }

docker info >/dev/null 2>&1 || { echo "Docker가 꺼져 있습니다. scripts/dev-up.sh 또는 Docker Desktop을 먼저 켜세요."; exit 1; }
# --wait: LocalStack 초기화(버킷·큐)가 끝난 뒤 파드를 올린다. 앱은 없는 큐를 만들지 않는다(I-1)
LS=gyeol-k3d-localstack LS_PORT=14566
if [ -z "$(docker ps -q -f name=^$LS$)" ]; then
  docker rm -f $LS >/dev/null 2>&1 || true
  docker run -d --name $LS -p $LS_PORT:4566 -e SERVICES=s3,sqs -e AWS_DEFAULT_REGION=ap-northeast-2 \
    -v "$PWD/local/localstack/init-aws.sh:/etc/localstack/init/ready.d/init-aws.sh:ro" localstack/localstack:4 >/dev/null
fi
for _ in $(seq 1 60); do docker exec $LS test -f /tmp/gyeol-init-ok 2>/dev/null && break; sleep 2; done
docker exec $LS test -f /tmp/gyeol-init-ok || { echo "k3d LocalStack 초기화 실패: docker logs $LS"; exit 1; }

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
    --from-literal=ADMIN_TOTP_KEY="$(sed -n 's/^ *totp-key: //p' backend/api/src/main/resources/application-local.yml)" \
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
