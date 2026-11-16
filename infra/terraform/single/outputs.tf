# Porkbun A 레코드 gyeol.blog → 이 주소
output "public_ip" { value = aws_eip.server.public_ip }
# GitHub 저장소 Variables에 넣는 값 (비밀 아님)
output "github_variables" {
  value = {
    AWS_DEPLOY_ROLE_ARN = aws_iam_role.deploy.arn
    AWS_REGION          = var.region
    SERVER_INSTANCE_ID  = aws_instance.server.id
  }
}
output "instance_id" { value = aws_instance.server.id }
output "ami" { value = "${data.aws_ami.ubuntu.name} (${data.aws_ami.ubuntu.id})" }
