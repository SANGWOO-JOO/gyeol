# 앱(api·worker·fetcher·백업 작업)이 쓰는 권한 하나. 단일 서버는 EC2 역할, EKS는 Pod Identity 역할에 붙인다
data "aws_iam_policy_document" "app" {
  statement {
    sid = "Queues"
    actions = [
      "sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility",
      "sqs:GetQueueUrl", "sqs:GetQueueAttributes",
    ]
    resources = concat([for q in aws_sqs_queue.main : q.arn], [for q in aws_sqs_queue.dlq : q.arn])
  }

  statement {
    sid       = "BucketList"
    actions   = ["s3:ListBucket"]
    resources = [for b in aws_s3_bucket.this : b.arn]
  }

  statement {
    sid       = "Objects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [for b in aws_s3_bucket.this : "${b.arn}/*"]
  }

  # global 추론 프로필은 요청을 여러 리전 모델로 보낸다: 프로필과 그 아래 모델(리전 없는 ARN 포함) 모두 허용
  statement {
    sid     = "Bedrock"
    actions = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
    resources = flatten([for m in var.bedrock_models : [
      "arn:aws:bedrock:${var.region}:${local.account}:inference-profile/global.${m}",
      "arn:aws:bedrock:*::foundation-model/${m}",
      "arn:aws:bedrock:::foundation-model/${m}",
    ]])
  }
}

resource "aws_iam_policy" "app" {
  name        = "gyeol-app"
  description = "gyeol 앱: SQS 큐, S3 버킷(업로드·표지·백업), Bedrock 모델 호출"
  policy      = data.aws_iam_policy_document.app.json
}
