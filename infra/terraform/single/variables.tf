variable "region" {
  type    = string
  default = "ap-northeast-2"
}

# base 상태를 읽을 상태 버킷 (backend.hcl의 bucket과 같은 값)
variable "state_bucket" {
  type = string
}

# 스팟 가격을 apply 직전에 조회해 고른다 (문서 2-2). EBS가 이 영역에 묶인다
variable "availability_zone" {
  type    = string
  default = "ap-northeast-2d"
}

variable "instance_type" {
  type    = string
  default = "t4g.medium"
}

variable "root_volume_gb" {
  type    = number
  default = 30
}

variable "domain" {
  type    = string
  default = "gyeol.blog"
}

# Let's Encrypt 계정 메일(만료 안내). tfvars(git 제외)에 적는다
variable "acme_email" {
  type = string
}

# /api/admin·/admin에 들어올 수 있는 IP (CIDR, 예: ["203.0.113.7/32"]). 비우면 관리자 경로를 닫는다
variable "admin_ips" {
  type    = list(string)
  default = []
}

# k3d 검증(11-16)과 같은 k3s
variable "k3s_version" {
  type    = string
  default = "v1.35.5+k3s1"
}

# CI(checks.yml)·맥북과 같은 helm
variable "helm_version" {
  type    = string
  default = "v4.3.1"
}

# OIDC 신뢰: 이 저장소의 GitHub Environment production에서 돈 작업만 (결정 S6)
variable "github_repo" {
  type    = string
  default = "SANGWOO-JOO/gyeol"
}
