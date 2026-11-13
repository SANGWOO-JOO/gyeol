variable "region" {
  type    = string
  default = "ap-northeast-2"
}

# 브라우저가 서명 URL로 업로드 버킷에 직접 PUT하는 웹 주소. 서버 주소가 정해지면 채운다 (예: ["https://gyeol.example"])
variable "web_origins" {
  type    = list(string)
  default = []
}

# worker가 Converse로 부르는 모델 (application.yml LLM_MODEL 기본값). global 추론 프로필
variable "bedrock_models" {
  type    = list(string)
  default = ["anthropic.claude-haiku-4-5-20251001-v1:0"]
}

# 비우면 예산 알림을 만들지 않는다. terraform.tfvars(git 제외)에 적는다
variable "budget_email" {
  type    = string
  default = ""
}

variable "budget_usd" {
  type    = number
  default = 20
}

# ③ 메일 발신 도메인 (예: "gyeol.example"). 비우면 SES 자원과 IAM 문장을 만들지 않는다 (ses.tf)
variable "mail_domain" {
  type    = string
  default = ""
}

# 보내는 주소의 @ 앞 (noreply@<mail_domain>). 앱 MAIL_FROM과 IAM ses:FromAddress 조건에 같이 쓴다
variable "mail_from_local" {
  type    = string
  default = "noreply"
}

# mail_domain이 Route 53 호스팅 영역이면 그 ID. 채우면 DKIM·SPF·DMARC 레코드까지 만들고, 비우면 output ses_dns_records로 직접 넣는다
variable "mail_route53_zone_id" {
  type    = string
  default = ""
}

# SES 샌드박스 동안 받을 수 있게 인증할 주소 (각 주소로 인증 메일이 간다). terraform.tfvars(git 제외)에 적는다
variable "ses_test_recipients" {
  type    = list(string)
  default = []
}
