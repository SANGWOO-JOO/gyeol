#!/bin/bash
# 서버 배포 스크립트: gyeol-deploy <태그>   (GitHub Actions가 SSM send-command로 부른다, 문서 6-2)
# 1) 태그 확인 2) 배포 전 DB 백업 3) 그 태그의 차트 받기 4) Parameter Store → Secret 5) helm upgrade 6) rollout 확인
# 비밀값은 출력하지 않는다 (set -x 쓰지 않음)
set -euo pipefail
TAG=${1:-}
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9.]+)?$ ]] || { echo "태그 형식이 아님: '$TAG'"; exit 2; }
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
export AWS_REGION=${AWS_REGION:-ap-northeast-2}
NS=gyeol
REPO=$(cat /etc/gyeol/github-repo)
log() { echo "[$(date +%H:%M:%S)] $*"; }

kubectl create namespace $NS --dry-run=client -o yaml | kubectl apply -f - >/dev/null

log "1) 배포 전 백업"
if kubectl -n $NS get cronjob gyeol-postgres-backup >/dev/null 2>&1 && kubectl -n $NS get statefulset gyeol-postgres >/dev/null 2>&1; then
  JOB="predeploy-$(echo "$TAG" | tr '.+' '--' | tr 'A-Z' 'a-z')-$(date +%s)"
  kubectl -n $NS create job --from=cronjob/gyeol-postgres-backup "$JOB" >/dev/null
  kubectl -n $NS wait --for=condition=complete --timeout=15m "job/$JOB" || { log "백업 실패 → 배포 중단"; kubectl -n $NS logs "job/$JOB" --all-containers --tail=30 || true; exit 3; }
else
  log "  첫 배포라 건너뜀"
fi

log "2) 차트 받기 ($REPO@$TAG)"
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
curl -fsSL "https://github.com/$REPO/archive/refs/tags/$TAG.tar.gz" | tar -xz -C "$WORK" --strip-components=1
CHART="$WORK/deploy/helm/gyeol"

log "3) 비밀값 (Parameter Store /gyeol/prod/ → gyeol-secrets)"
ARGS=()
while IFS=$'\t' read -r name value; do
  [ -n "$name" ] && ARGS+=("--from-literal=${name##*/}=$value")
done < <(aws ssm get-parameters-by-path --path /gyeol/prod/ --with-decryption --query 'Parameters[].[Name,Value]' --output text)
printf '%s\n' "${ARGS[@]}" | grep -q '^--from-literal=DB_PASSWORD=' || { log "/gyeol/prod/DB_PASSWORD 없음 → 중단"; exit 4; }
kubectl -n $NS create secret generic gyeol-secrets "${ARGS[@]}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
log "  키 ${#ARGS[@]}개"

log "4) helm upgrade"
helm upgrade --install gyeol "$CHART" -n $NS \
  -f "$CHART/values-single.yaml" -f /etc/gyeol/values-prod.yaml \
  --set image.tag="$TAG" --wait --timeout 15m

log "5) rollout 확인"
for d in api worker fetcher web embed; do kubectl -n $NS rollout status "deploy/gyeol-$d" --timeout=5m; done
log "완료: $TAG"
