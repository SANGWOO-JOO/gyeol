# 버킷 3개 (모두 비공개, 기본 암호화). 이름은 전역에서 겹치지 않게 계정 ID를 넣는다
#   uploads: 브라우저가 서명 URL로 올리는 Takeout 파일 (uploads/ → parse 큐)
#   covers:  앨범 표지·가수 이미지 (API가 결과 링크 토큰을 확인한 뒤 전달)
#   backups: PostgreSQL 덤프 (db/, 업로드 버킷과 분리: 객체 생성 알림이 parse 큐로 가지 않게)
locals {
  buckets = toset(["uploads", "covers", "backups"])
}

resource "aws_s3_bucket" "this" {
  for_each = local.buckets
  bucket   = "${local.prefix}-${each.key}"
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each                = local.buckets
  bucket                  = aws_s3_bucket.this[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = local.buckets
  bucket   = aws_s3_bucket.this[each.key].id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

# 업로드 파일은 worker가 처리 뒤 지운다. 남은 파일은 하루 뒤 삭제 (안전장치)
resource "aws_s3_bucket_lifecycle_configuration" "uploads" {
  bucket = aws_s3_bucket.this["uploads"].id
  rule {
    id     = "expire-uploads"
    status = "Enabled"
    filter { prefix = "uploads/" }
    expiration { days = 1 }
    abort_incomplete_multipart_upload { days_after_initiation = 1 }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.this["backups"].id
  rule {
    id     = "expire-db-dumps"
    status = "Enabled"
    filter { prefix = "db/" }
    expiration { days = 14 }
  }
}

resource "aws_s3_bucket_cors_configuration" "uploads" {
  count  = length(var.web_origins) > 0 ? 1 : 0
  bucket = aws_s3_bucket.this["uploads"].id
  cors_rule {
    allowed_origins = var.web_origins
    allowed_methods = ["PUT"]
    allowed_headers = ["*"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_notification" "uploads" {
  bucket = aws_s3_bucket.this["uploads"].id
  queue {
    queue_arn     = aws_sqs_queue.main["parse"].arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = "uploads/"
  }
  depends_on = [aws_sqs_queue_policy.parse_from_s3]
}
