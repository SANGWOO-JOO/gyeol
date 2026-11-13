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
