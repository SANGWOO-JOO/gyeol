# GitHub Actions → OIDC → gyeol-deploy → 이 서버에만 SSM send-command (cicd.md 2절, 결정 S6)
# GitHub OIDC 공급자는 이 스택이 직접 만든다.
# 11-17에는 다른 프로젝트와 공유해 data로 참조했으나, 11-18 00:07 다른 CloudFormation 스택 삭제로 공급자가 함께 지워져 v0.2.0 배포가 실패했다.
# 계정당 URL 하나만 둘 수 있으므로 다른 프로젝트는 이 공급자를 참조해야 한다.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "deploy_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # Environment production에서 돈 작업만. 저장소가 immutable subject(소유자·저장소 ID 포함)를 쓰므로 두 형식을 모두 허용 (11-17 첫 배포에서 AssumeRole 거절) (태그 배포·수동 롤백 둘 다 이 Environment를 쓴다)
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:environment:production", "${var.github_oidc_sub_prefix}:environment:production"]
    }
  }
}

resource "aws_iam_role" "deploy" {
  name                 = "gyeol-deploy"
  assume_role_policy   = data.aws_iam_policy_document.deploy_assume.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "deploy" {
  statement {
    sid     = "RunDeployScriptOnThisServer"
    actions = ["ssm:SendCommand"]
    resources = [
      aws_instance.server.arn,
      "arn:aws:ssm:${var.region}::document/AWS-RunShellScript",
    ]
  }
  # 결과 조회는 자원 단위로 좁힐 수 없다고 알려져 있다(확인 필요). 읽기만
  statement {
    sid       = "ReadCommandResult"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "gyeol-deploy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}
