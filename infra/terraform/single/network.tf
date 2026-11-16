# 기본 VPC 공용 서브넷 (S3). NAT 없음
data "aws_vpc" "default" {
  default = true
}

# 11-17 확인: 이 계정 기본 VPC에는 IGW·0.0.0.0/0 경로는 있으나 서브넷이 0개다.
# aws_default_subnet은 그 영역의 기본 서브넷이 없으면 만들고(공인 IP 자동 할당), 있으면 가져온다. 서브넷 자체는 무료
resource "aws_default_subnet" "public" {
  availability_zone = var.availability_zone
  force_destroy     = true # destroy 때 지운다 (원래 없던 것)
  tags              = { Name = "${local.name}-${var.availability_zone}" }
}

# 80(Let's Encrypt HTTP-01, HTTPS로 넘김)·443만. SSH·6443은 열지 않고 SSM Session Manager로만 들어간다
resource "aws_security_group" "server" {
  name        = local.name
  description = "gyeol single server: 80/443 only"
  vpc_id      = data.aws_vpc.default.id
}

resource "aws_vpc_security_group_ingress_rule" "web" {
  for_each          = toset(["80", "443"])
  security_group_id = aws_security_group.server.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.key)
  to_port           = tonumber(each.key)
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.server.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# 스팟이 정지·시작돼도 주소가 그대로 (S1). Porkbun A 레코드에 이 주소를 넣는다
resource "aws_eip" "server" {
  domain   = "vpc"
  instance = aws_instance.server.id
  tags     = { Name = local.name }
}
