# 서버 역할: base 앱 정책(SQS·S3·Bedrock Haiku·SES) + SSM 관리 + 비밀값 읽기 + 선물 이미지 모델
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "server" {
  name               = "gyeol-server"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "app" {
  role       = aws_iam_role.server.name
  policy_arn = local.base.app_policy_arn
}

# Session Manager 접속·send-command (SSH 대신)
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.server.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "server_extra" {
  # 비밀값: 사용자가 직접 넣은 /gyeol/prod/* (Terraform 상태에 값이 남지 않게 여기서 만들지 않음)
  statement {
    sid       = "ReadSecrets"
    actions   = ["ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.region}:${local.account}:parameter/gyeol/prod", "arn:aws:ssm:${var.region}:${local.account}:parameter/gyeol/prod/*"]
  }
  statement {
    sid       = "DecryptSecrets"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.region}.amazonaws.com"]
    }
  }
  # ④ 선물 캐릭터 이미지 (us-west-2, Marketplace 구독은 사용자가 콘솔에서 먼저)
  statement {
    sid       = "GiftImageModel"
    actions   = ["bedrock:InvokeModel"]
    resources = ["arn:aws:bedrock:us-west-2::foundation-model/stability.stable-image-core-v1:1"]
  }
}

resource "aws_iam_role_policy" "server_extra" {
  name   = "gyeol-server-extra"
  role   = aws_iam_role.server.id
  policy = data.aws_iam_policy_document.server_extra.json
}

resource "aws_iam_instance_profile" "server" {
  name = "gyeol-server"
  role = aws_iam_role.server.name
}
