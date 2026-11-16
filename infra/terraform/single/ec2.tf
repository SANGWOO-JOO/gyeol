# Ubuntu 24.04 LTS arm64 (Canonical 공식, S2). SSM 에이전트가 기본으로 들어 있다
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-arm64-server-*"]
  }
  filter {
    name   = "architecture"
    values = ["arm64"]
  }
}

resource "aws_instance" "server" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  subnet_id              = aws_default_subnet.public.id
  vpc_security_group_ids = [aws_security_group.server.id]
  iam_instance_profile   = aws_iam_instance_profile.server.name

  # 회수되면 정지(EBS 유지) → 용량이 생기면 다시 시작. 최고 가격은 지정하지 않음(온디맨드가 상한)
  instance_market_options {
    market_type = "spot"
    spot_options {
      spot_instance_type             = "persistent"
      instance_interruption_behavior = "stop"
    }
  }

  # 파드가 서버 역할 자격 증명을 받으려면 홉 2 (컨테이너 안에서 IMDSv2)
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_gb
    encrypted             = true
    delete_on_termination = false # S8: DB 데이터가 여기 있다. 완전히 내릴 때는 손으로 지운다(문서 3-2)
    tags                  = { Name = local.name }
  }

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    k3s_version  = var.k3s_version
    helm_version = var.helm_version
    acme_email   = var.acme_email
    values_prod = templatefile("${path.module}/values-prod.yaml.tftpl", {
      domain        = var.domain
      upload_bucket = local.base.upload_bucket
      cover_bucket  = local.base.cover_bucket
      backup_bucket = local.base.backup_bucket
      mail_from     = local.base.mail_from
      mail_config   = local.base.mail_configuration_set
      admin_ips     = var.admin_ips
    })
    deploy_script = file("${path.module}/gyeol-deploy.sh")
    github_repo   = var.github_repo
  })

  # AMI가 새로 나오거나 user-data를 고쳐도 서버를 다시 만들지 않는다. OS 갱신·스크립트 교체는 서버 안에서
  lifecycle {
    ignore_changes = [ami, user_data]
  }

  tags = { Name = local.name }
}
