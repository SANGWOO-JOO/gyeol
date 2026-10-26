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
  --attributes "{\"VisibilityTimeout\":\"300\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$CQ_DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# 업로드 버킷. 브라우저가 서명 URL로 직접 올리므로 로컬 웹 주소(Vite 15173, k3d 18000)에 CORS를 열어 둔다.
awslocal s3 mb "s3://$BUCKET" --region "$REGION" >/dev/null
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

echo "gyeol-local: 버킷 $BUCKET, 큐 $QUEUE (+ $DLQ), $IDQ (+ $IDQ_DLQ), $CQ (+ $CQ-dlq), S3 → SQS 알림 준비 완료"
