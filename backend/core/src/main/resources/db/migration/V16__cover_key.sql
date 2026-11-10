-- 표지 이미지를 미리 받아 둔 S3 키. 비어 있으면 표지 주소(cover_url)를 브라우저가 직접 불러온다.
ALTER TABLE track ADD COLUMN cover_key TEXT;
