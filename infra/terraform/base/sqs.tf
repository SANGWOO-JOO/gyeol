# 큐 이름은 Helm 차트 기본값(gyeol-parse 등)과 같다. 값은 처리 중 다른 소비자에게 보이지 않는 시간(초)
locals {
  queues = {
    parse          = 120 # Takeout 파일 하나 파싱
    identify       = 900 # 곡 하나에 수십 초
    cover          = 900
    enrich         = 900 # 곡 몇 개에 외부 호출 수십 번
    recommend      = 900 # 후보 5곡 식별·보강
    guess          = 300
    "artist-image" = 600
  }
}

resource "aws_sqs_queue" "dlq" {
  for_each                  = local.queues
  name                      = "gyeol-${each.key}-dlq"
  message_retention_seconds = 1209600 # 14일: 실패 원인을 볼 시간
  sqs_managed_sse_enabled   = true
}

# 3번 실패하면 실패 큐로 옮긴다
resource "aws_sqs_queue" "main" {
  for_each                   = local.queues
  name                       = "gyeol-${each.key}"
  visibility_timeout_seconds = each.value
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = 3
  })
}

# 업로드 버킷의 객체 생성 알림을 parse 큐가 받도록 S3에 보내기 권한
resource "aws_sqs_queue_policy" "parse_from_s3" {
  queue_url = aws_sqs_queue.main["parse"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.main["parse"].arn
      Condition = {
        ArnEquals    = { "aws:SourceArn" = aws_s3_bucket.this["uploads"].arn }
        StringEquals = { "aws:SourceAccount" = local.account }
      }
    }]
  })
}
