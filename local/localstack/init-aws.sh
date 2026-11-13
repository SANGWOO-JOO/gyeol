#!/bin/bash
# LocalStack 준비 직후 실행된다. 이름은 실제 AWS와 섞이지 않게 gyeol-local-* 로 둔다.
set -euo pipefail

REGION=ap-northeast-2
BUCKET=gyeol-local-uploads
QUEUE=gyeol-local-parse
DLQ=gyeol-local-parse-dlq

# 실패 큐(DLQ)와 parse 큐. 3번 실패하면 실패 큐로 옮긴다.
awslocal sqs create-queue --queue-name "$DLQ" --region "$REGION" >/dev/null
DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$DLQ" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

awslocal sqs create-queue --queue-name "$QUEUE" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"120\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null
QUEUE_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$QUEUE" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

# 곡 식별 큐 (L2-1). 곡 하나에 수십 초가 걸릴 수 있어 표시 제한 시간을 15분으로 둔다.
IDQ=gyeol-local-identify
IDQ_DLQ=gyeol-local-identify-dlq
awslocal sqs create-queue --queue-name "$IDQ_DLQ" --region "$REGION" >/dev/null
IDQ_DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$IDQ_DLQ" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name "$IDQ" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"900\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$IDQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# 앨범 표지 큐 (L2-2). 실패해도 화면은 글자 타일로 남으므로 실패 큐는 기록용
CQ=gyeol-local-cover
awslocal sqs create-queue --queue-name "$CQ-dlq" --region "$REGION" >/dev/null
CQ_DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$CQ-dlq" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name "$CQ" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"900\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$CQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# 지식 인입 큐 (L3-1). 곡 몇 개에 외부 호출이 수십 번이라 표시 제한 시간을 15분으로
EQ=gyeol-local-enrich
awslocal sqs create-queue --queue-name "$EQ-dlq" --region "$REGION" >/dev/null
EQ_DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$EQ-dlq" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name "$EQ" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"900\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$EQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# 추천 큐 (L3-4). 후보 5곡 식별·보강이 한 메시지라 15분
RQ=gyeol-local-recommend
awslocal sqs create-queue --queue-name "$RQ-dlq" --region "$REGION" >/dev/null
RQ_DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$RQ-dlq" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name "$RQ" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"900\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$RQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# 음악 방 담기 식별 큐. 곡 하나 식별이 길면 1~2분(측정 최대 82초)이라 15분, 실패 큐에서 요청을 FAILED로
MQ=gyeol-local-room-song
awslocal sqs create-queue --queue-name "$MQ-dlq" --region "$REGION" >/dev/null
MQ_DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$MQ-dlq" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name "$MQ" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"900\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$MQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# ④ 50곡 선물 캐릭터 큐. 이미지 생성이 수십 초라 5분, 3번 실패하면 실패 큐에서 상자를 FAILED로
GFQ=gyeol-local-gift-image
awslocal sqs create-queue --queue-name "$GFQ-dlq" --region "$REGION" >/dev/null
GFQ_DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$GFQ-dlq" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name "$GFQ" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"300\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$GFQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# ⑤ 코드 맞추기 이유 큐. LLM 한 번이라 2분, 3번 실패하면 실패 큐에서 이유 없이 끝냄
CMQ=gyeol-local-code-match
awslocal sqs create-queue --queue-name "$CMQ-dlq" --region "$REGION" >/dev/null
CMQ_DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$CMQ-dlq" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name "$CMQ" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"120\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$CMQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# 방송·일반 채널 영상의 가수·곡 뽑기 큐. 실패해도 해당 재생이 가수 순위에서 빠질 뿐이라 실패 큐는 기록용
GQ=gyeol-local-guess
awslocal sqs create-queue --queue-name "$GQ-dlq" --region "$REGION" >/dev/null
GQ_DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$GQ-dlq" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name "$GQ" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"300\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$GQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# 가수 이미지 큐. 실패해도 화면은 대표 앨범 표지나 글자 타일로 남아 실패 큐는 기록용
AQ=gyeol-local-artist-image
awslocal sqs create-queue --queue-name "$AQ-dlq" --region "$REGION" >/dev/null
AQ_DLQ_ARN=$(awslocal sqs get-queue-attributes --region "$REGION" \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "$AQ-dlq" --region "$REGION" --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name "$AQ" --region "$REGION" \
  --attributes "{\"VisibilityTimeout\":\"600\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$AQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# 업로드 버킷. 브라우저가 서명 URL로 직접 올리므로 로컬 웹 주소(Vite 15173, k3d 18000)에 CORS를 열어 둔다.
awslocal s3 mb "s3://$BUCKET" --region "$REGION" >/dev/null
# 표지 이미지 보관 (비공개). API가 결과 링크 토큰을 확인한 뒤 전달한다
awslocal s3 mb "s3://gyeol-local-covers" --region "$REGION" >/dev/null
awslocal s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration '{
  "CORSRules": [{
    "AllowedOrigins": ["http://localhost:15173", "http://localhost:18000"],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["*"],
    "MaxAgeSeconds": 3000
  }]
}'

# uploads/ 아래에 파일이 생기면 parse 큐로 알린다.
awslocal s3api put-bucket-notification-configuration --bucket "$BUCKET" --notification-configuration "{
  \"QueueConfigurations\": [{
    \"QueueArn\": \"$QUEUE_ARN\",
    \"Events\": [\"s3:ObjectCreated:*\"],
    \"Filter\": {\"Key\": {\"FilterRules\": [{\"Name\": \"prefix\", \"Value\": \"uploads/\"}]}}
  }]
}"

echo "gyeol-local: 버킷 $BUCKET, 큐 $QUEUE (+ $DLQ), $IDQ (+ $IDQ_DLQ), $CQ (+ $CQ-dlq), $EQ (+ $EQ-dlq), S3 → SQS 알림 준비 완료"
