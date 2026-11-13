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
