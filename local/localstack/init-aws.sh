#!/bin/bash
# LocalStack 준비 직후 실행된다. 이름은 실제 AWS와 섞이지 않게 gyeol-local-* 로 둔다.
# 몇 번 다시 실행해도 같은 결과가 되게 만든다(멱등, 11-14 I-1):
#  - 이미 있는 버킷은 건너뛰고, 이미 있는 큐는 표시 제한·실패 큐 설정을 덮어쓴다
#    (전에는 앱이 먼저 기본값으로 만든 큐에 create-queue가 QueueAlreadyExists로 실패해 스크립트가 멈추고 버킷·알림이 빠졌다)
#  - 항목 하나가 실패해도 나머지를 계속 만들고, 마지막 검사에서 빠진 것 이름을 출력하고 실패 코드로 끝낸다
#  - 모두 갖춰지면 /tmp/gyeol-init-ok 를 만든다 (compose 상태 검사가 이 파일을 본다)
set -uo pipefail

REGION=ap-northeast-2
UPLOADS=gyeol-local-uploads
COVERS=gyeol-local-covers
READY=/tmp/gyeol-init-ok
rm -f "$READY"
FAILED=()

aws_() { awslocal "$@" --region "$REGION"; }
queue_url() { aws_ sqs get-queue-url --queue-name "$1" --query QueueUrl --output text 2>/dev/null; }
queue_arn() { aws_ sqs get-queue-attributes --queue-url "$(queue_url "$1")" --attribute-names QueueArn --query Attributes.QueueArn --output text; }

# 버킷: 있으면 건너뛴다
make_bucket() {
  if aws_ s3api head-bucket --bucket "$1" >/dev/null 2>&1; then return 0; fi
  aws_ s3 mb "s3://$1" >/dev/null || { echo "버킷 만들기 실패: $1" >&2; FAILED+=("bucket:$1"); }
}

# 큐 하나 + 실패 큐 (3번 실패하면 옮김). 본 큐가 이미 있으면 설정을 덮어쓴다
# make_queue 이름 표시제한초
make_queue() {
  local name=$1 visibility=$2 dlq_arn attrs
  if [ -z "$(queue_url "$name-dlq")" ]; then
    aws_ sqs create-queue --queue-name "$name-dlq" >/dev/null || { echo "실패 큐 만들기 실패: $name-dlq" >&2; FAILED+=("queue:$name-dlq"); return 0; }
  fi
  dlq_arn=$(queue_arn "$name-dlq") || { FAILED+=("queue:$name-dlq"); return 0; }
  attrs="{\"VisibilityTimeout\":\"$visibility\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"
  if [ -n "$(queue_url "$name")" ]; then
    aws_ sqs set-queue-attributes --queue-url "$(queue_url "$name")" --attributes "$attrs" || { echo "큐 설정 덮어쓰기 실패: $name" >&2; FAILED+=("queue:$name"); }
  else
    aws_ sqs create-queue --queue-name "$name" --attributes "$attrs" >/dev/null || { echo "큐 만들기 실패: $name" >&2; FAILED+=("queue:$name"); }
  fi
}

# ── 버킷 (큐보다 먼저: 큐에서 멈춰도 버킷은 생기게) ──
# 업로드: 브라우저가 서명 URL로 직접 올리므로 로컬 웹 주소(Vite 15173, k3d 18000)에 CORS를 연다
make_bucket "$UPLOADS"
# 표지·가수 사진·선물 캐릭터 (비공개). API가 권한을 확인한 뒤 전달한다
make_bucket "$COVERS"
aws_ s3api put-bucket-cors --bucket "$UPLOADS" --cors-configuration '{
  "CORSRules": [{
    "AllowedOrigins": ["http://localhost:15173", "http://localhost:18000"],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["*"],
    "MaxAgeSeconds": 3000
  }]
}' || FAILED+=("cors:$UPLOADS")

# ── 큐 (이름, 처리 중 다른 소비자에게 보이지 않는 시간 초) ──
make_queue gyeol-local-parse 120          # Takeout 파일 하나 파싱
make_queue gyeol-local-identify 900       # 곡 식별 (L2-1), 곡 하나에 수십 초
make_queue gyeol-local-cover 900          # 앨범 표지 (L2-2), 실패 큐는 기록용
make_queue gyeol-local-enrich 900         # 지식 인입 (L3-1), 외부 호출 수십 번
make_queue gyeol-local-recommend 900      # 추천 (L3-4), 후보 5곡 식별·보강
make_queue gyeol-local-room-song 900      # 음악 방 담기 식별 (측정 최대 82초), 실패 큐에서 요청을 FAILED로
make_queue gyeol-local-gift-image 300     # ④ 선물 캐릭터, 이미지 생성 수십 초, 실패 큐에서 상자를 FAILED로
make_queue gyeol-local-code-match 120     # ⑤ 코드 맞추기 이유, LLM 한 번, 실패 큐에서 이유 없이 끝냄
make_queue gyeol-local-guess 300          # 방송·일반 영상 가수·곡 뽑기, 실패 큐는 기록용
make_queue gyeol-local-artist-image 600   # 가수 이미지, 실패 큐는 기록용

# ── uploads/ 아래에 파일이 생기면 parse 큐로 알린다 ──
if PARSE_ARN=$(queue_arn gyeol-local-parse 2>/dev/null) && [ -n "$PARSE_ARN" ]; then
  aws_ s3api put-bucket-notification-configuration --bucket "$UPLOADS" --notification-configuration "{
    \"QueueConfigurations\": [{
      \"QueueArn\": \"$PARSE_ARN\",
      \"Events\": [\"s3:ObjectCreated:*\"],
      \"Filter\": {\"Key\": {\"FilterRules\": [{\"Name\": \"prefix\", \"Value\": \"uploads/\"}]}}
    }]
  }" || FAILED+=("notification:$UPLOADS")
else
  FAILED+=("notification:$UPLOADS")
fi

# ── 기대 목록 검사: 버킷 2, 큐 10쌍(표시 제한·실패 큐 연결까지), 업로드 알림 ──
MISSING=()
for b in "$UPLOADS" "$COVERS"; do
  aws_ s3api head-bucket --bucket "$b" >/dev/null 2>&1 || MISSING+=("bucket:$b")
done
for q in parse:120 identify:900 cover:900 enrich:900 recommend:900 room-song:900 gift-image:300 code-match:120 guess:300 artist-image:600; do
  name="gyeol-local-${q%%:*}"; want="${q##*:}"
  [ -n "$(queue_url "$name-dlq")" ] || { MISSING+=("queue:$name-dlq"); continue; }
  url=$(queue_url "$name")
  [ -n "$url" ] || { MISSING+=("queue:$name"); continue; }
  got=$(aws_ sqs get-queue-attributes --queue-url "$url" --attribute-names VisibilityTimeout RedrivePolicy --output json 2>/dev/null)
  echo "$got" | grep -q "\"VisibilityTimeout\": \"$want\"" || MISSING+=("visibility:$name")
  echo "$got" | grep -q "$name-dlq" || MISSING+=("redrive:$name")
done
aws_ s3api get-bucket-notification-configuration --bucket "$UPLOADS" 2>/dev/null | grep -q "gyeol-local-parse" || MISSING+=("notification:$UPLOADS")

if [ ${#MISSING[@]} -gt 0 ]; then
  echo "gyeol-local: 준비 실패. 빠진 것: ${MISSING[*]} (실행 중 오류: ${FAILED[*]:-없음})" >&2
  exit 1
fi
touch "$READY"
echo "gyeol-local: 버킷 2, 큐 10쌍, 업로드 → parse 알림 준비 완료"
