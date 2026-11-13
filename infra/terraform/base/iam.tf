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

  # ③ 방장 인증 메일. mail_domain이 있을 때만. 보내는 주소는 noreply@<도메인> 하나로 묶는다.
  # SES v2 SendEmail은 발신 identity와 설정 세트에 권한을 확인하고, 샌드박스에서는 받는 사람 identity도 확인한다(추정, 문서 기준)
  dynamic "statement" {
    for_each = local.mail_on ? [1] : []
    content {
      sid     = "SendMail"
      actions = ["ses:SendEmail"]
      resources = concat(
        [
          aws_sesv2_email_identity.domain[0].arn,
          aws_sesv2_configuration_set.main[0].arn,
        ],
        [for r in aws_sesv2_email_identity.test_recipient : r.arn],
      )
      condition {
        test     = "StringEquals"
        variable = "ses:FromAddress"
        values   = [local.mail_from]
      }
    }
  }
}

resource "aws_iam_policy" "app" {
  name        = "gyeol-app"
  description = "gyeol 앱: SQS 큐, S3 버킷(업로드·표지·백업), Bedrock 모델 호출, SES 메일(도메인 있을 때)"
  policy      = data.aws_iam_policy_document.app.json
}
