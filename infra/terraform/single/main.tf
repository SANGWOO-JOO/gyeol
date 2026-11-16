# 단일 서버 상시 운영: EC2 t4g.medium 스팟 + k3s 한 대 (docs/todo/single-server-terraform.md, 결정 S1~S12)
# base 스택(SQS·S3·앱 IAM 정책·SES)의 출력을 읽어 쓴다. 이 스택을 지워도 base는 남는다.
#   cp backend.hcl.example backend.hcl && cp terraform.tfvars.example terraform.tfvars   # 둘 다 git 제외
#   terraform init -backend-config=backend.hcl && terraform plan
# 11-17 최소 구성: 예산 자동 조치(S7)·모니터링(16절)은 넣지 않았다(이유는 문서 17절).
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
  backend "s3" {
    key          = "single/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region
  default_tags { tags = { Project = "gyeol", Stack = "single" } }
}

data "aws_caller_identity" "me" {}

# base 출력: 버킷 이름(계정 ID가 들어감)·앱 정책·메일 설정을 손으로 옮기지 않는다
data "terraform_remote_state" "base" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = "base/terraform.tfstate"
    region = "ap-northeast-2"
  }
}

locals {
  account = data.aws_caller_identity.me.account_id
  base    = data.terraform_remote_state.base.outputs
  name    = "gyeol-single"
}
