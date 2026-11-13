output "upload_bucket" { value = aws_s3_bucket.this["uploads"].bucket }
output "cover_bucket" { value = aws_s3_bucket.this["covers"].bucket }
output "backup_bucket" { value = aws_s3_bucket.this["backups"].bucket }
output "app_policy_arn" { value = aws_iam_policy.app.arn }
output "queues" { value = { for k, q in aws_sqs_queue.main : k => q.name } }

# Helm에 넘길 값 (큐 이름은 차트 기본값과 같아 버킷만)
output "helm_set" {
  value = join(",", [
    "aws.uploadBucket=${aws_s3_bucket.this["uploads"].bucket}",
    "aws.coverBucket=${aws_s3_bucket.this["covers"].bucket}",
    "postgres.backup.bucket=${aws_s3_bucket.this["backups"].bucket}",
  ])
}

# ③ 메일 (mail_domain이 비면 빈 값)
output "mail_from" { value = local.mail_from }
output "mail_configuration_set" { value = local.mail_on ? aws_sesv2_configuration_set.main[0].configuration_set_name : "" }
# Route 53을 쓰지 않을 때 DNS에 직접 넣을 레코드
output "ses_dns_records" { value = values(local.mail_dns_records) }
