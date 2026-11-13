# Terraform 상태 파일을 둘 S3 버킷. 처음 한 번만 만든다 (이 묶음의 상태는 로컬 파일, git 제외)
#   terraform init && terraform apply
# 출력된 버킷 이름을 base/backend.hcl 등에 적는다 (backend.hcl.example 참고)
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}

provider "aws" {
  region = var.region
  default_tags { tags = { Project = "gyeol", Stack = "bootstrap" } }
}

variable "region" {
  type    = string
  default = "ap-northeast-2"
}

data "aws_caller_identity" "me" {}

resource "aws_s3_bucket" "state" {
  bucket = "gyeol-tfstate-${data.aws_caller_identity.me.account_id}"
  lifecycle { prevent_destroy = true }
}

# 실수로 덮어쓴 상태를 되돌릴 수 있게 버전 관리
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    id     = "old-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration { noncurrent_days = 30 }
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "state_bucket" { value = aws_s3_bucket.state.bucket }
