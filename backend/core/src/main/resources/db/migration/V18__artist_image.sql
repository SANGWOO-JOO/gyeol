-- 가수 이미지 (docs/todo/artist-images.md). 사용자와 연결되지 않는 전역 캐시.
-- COMMONS: S3에 저장한 Wikimedia Commons 사진 (저작자·라이선스 표시), YOUTUBE: 채널 프로필 주소만 (30일 안에 갱신), NONE: 둘 다 없음
CREATE TABLE artist_image (
    artist_id  BIGINT      PRIMARY KEY REFERENCES artist (id) ON DELETE CASCADE,
    source     VARCHAR(16) NOT NULL CHECK (source IN ('COMMONS', 'YOUTUBE', 'NONE')),
    s3_key     TEXT,
    url        TEXT,
    page_url   TEXT,
    author     TEXT,
    license    TEXT,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
