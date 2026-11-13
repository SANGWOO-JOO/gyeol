# ③ 방장 인증 메일 (SES v2). mail_domain이 비어 있으면 아무것도 만들지 않는다 (plan 변화 0, 비용 0).
# 도메인을 정한 뒤:
#   1) terraform.tfvars에 mail_domain(+ Route 53이면 mail_route53_zone_id) 적고 apply
#   2) Route 53이 아니면 output ses_dns_records 값을 DNS에 직접 넣기 → DKIM 인증 완료까지 기다리기
#   3) 샌드박스 동안은 ses_test_recipients에 넣은 주소(인증 메일 링크 클릭)로만 받을 수 있다. 해제는 콘솔에서 신청
# 앱 설정: MAIL_MODE=ses, MAIL_FROM=output mail_from, MAIL_CONFIG_SET=output mail_configuration_set
# 비용(추정): 발송 1,000통당 약 0.10달러. identity·설정 세트 자체는 무료

locals {
  mail_on        = var.mail_domain != ""
  mail_from      = local.mail_on ? "${var.mail_from_local}@${var.mail_domain}" : ""
  mail_from_sub  = "mail.${var.mail_domain}" # 반송 주소(MAIL FROM) 하위 도메인: SPF를 발신 도메인과 맞춘다
  mail_dns_route = local.mail_on && var.mail_route53_zone_id != ""

  # DKIM CNAME 3개 + MAIL FROM MX·SPF + DMARC(p=none으로 시작, 보고만).
  # 키는 고정(dkim0~2…): DKIM 토큰은 apply 전에 알 수 없어 for_each 키로 쓸 수 없다
  mail_dns_records = local.mail_on ? merge(
    { for i in range(3) : "dkim${i}" => {
      name  = "${aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].tokens[i]}._domainkey.${var.mail_domain}"
      type  = "CNAME"
      value = "${aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].tokens[i]}.dkim.amazonses.com"
    } },
    {
      mx    = { name = local.mail_from_sub, type = "MX", value = "10 feedback-smtp.${var.region}.amazonses.com" }
      spf   = { name = local.mail_from_sub, type = "TXT", value = "v=spf1 include:amazonses.com ~all" }
      dmarc = { name = "_dmarc.${var.mail_domain}", type = "TXT", value = "v=DMARC1; p=none;" }
    },
  ) : {}
}

resource "aws_sesv2_email_identity" "domain" {
  count          = local.mail_on ? 1 : 0
  email_identity = var.mail_domain
  dkim_signing_attributes {
    next_signing_key_length = "RSA_2048_BIT"
  }
}

resource "aws_sesv2_email_identity_mail_from_attributes" "domain" {
  count                  = local.mail_on ? 1 : 0
  email_identity         = aws_sesv2_email_identity.domain[0].email_identity
  mail_from_domain       = local.mail_from_sub
  behavior_on_mx_failure = "USE_DEFAULT_VALUE" # MX가 아직 없으면 amazonses.com 반송 주소로 보낸다
}

# 발송 지표(평판)를 모으는 묶음. 앱은 MAIL_CONFIG_SET으로 이 이름을 붙여 보낸다
resource "aws_sesv2_configuration_set" "main" {
  count                  = local.mail_on ? 1 : 0
  configuration_set_name = "gyeol"
  reputation_options {
    reputation_metrics_enabled = true
  }
  sending_options {
    sending_enabled = true
  }
}

# 주의: 계정 단위 설정이다. 이 계정의 SES 발송 전체에 적용된다(개인 계정이라 괜찮음).
# 반송·불만이 난 주소는 SES가 자동으로 다시 보내지 않는다 (샌드박스 해제 신청 때 반송 처리 근거)
resource "aws_sesv2_account_suppression_attributes" "main" {
  count              = local.mail_on ? 1 : 0
  suppressed_reasons = ["BOUNCE", "COMPLAINT"]
}

# 샌드박스 동안 받을 수 있는 주소 (apply 후 각 주소로 온 인증 메일의 링크를 눌러야 한다)
resource "aws_sesv2_email_identity" "test_recipient" {
  for_each       = local.mail_on ? toset(var.ses_test_recipients) : toset([])
  email_identity = each.value
}

resource "aws_route53_record" "mail" {
  for_each = local.mail_dns_route ? toset(["dkim0", "dkim1", "dkim2", "mx", "spf", "dmarc"]) : toset([])
  zone_id  = var.mail_route53_zone_id
  name     = local.mail_dns_records[each.key].name
  type     = local.mail_dns_records[each.key].type
  ttl      = 600
  records  = [local.mail_dns_records[each.key].value]
}
