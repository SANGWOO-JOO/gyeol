# 계속 유지하는 공통 자원: SQS 큐·실패 큐, S3 버킷(업로드·표지·백업), 앱 IAM 정책, 예산 알림.
# 단일 서버(single)와 EKS(eks)가 함께 쓰고, 둘을 지워도 이 묶음은 남는다. 쓰지 않으면 비용은 거의 0.
#   cp backend.hcl.example backend.hcl   # 상태 버킷 이름 채우기 (git 제외)
#   terraform init -backend-config=backend.hcl && terraform plan
# 큐 설정은 local/localstack/init-aws.sh와 같게 맞춘다.
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
  # 버킷 이름은 backend.hcl에서 (계정 ID가 들어가 저장소에 넣지 않음). use_lockfile: S3 자체 잠금 (DynamoDB 불필요)
  backend "s3" {
    key          = "base/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region
  default_tags { tags = { Project = "gyeol", Stack = "base" } }
}

data "aws_caller_identity" "me" {}

locals {
  account = data.aws_caller_identity.me.account_id
  prefix  = "gyeol-${local.account}"
}
