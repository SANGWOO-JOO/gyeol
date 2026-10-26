-- 정리 작업이 S3 원본 파일을 지운 시각. 지운 파일은 다시 지우지 않는다.
ALTER TABLE upload ADD COLUMN file_deleted_at TIMESTAMPTZ;

CREATE INDEX idx_upload_file_pending ON upload (created_at) WHERE file_deleted_at IS NULL;
