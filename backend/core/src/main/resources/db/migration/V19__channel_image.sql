-- 가수 기록 없이 Topic 채널로만 들은 가수의 이미지 (docs/todo/artist-images.md). 채널 이름 기준 전역 캐시.
-- 가수 기록(artist)은 만들지 않는다: 이름 검색으로 찾은 MusicBrainz 가수는 사진을 찾는 데만 쓰고 artist_mbid에 남긴다
CREATE TABLE channel_image (
    id          BIGSERIAL   PRIMARY KEY,
    channel     TEXT        NOT NULL UNIQUE,
    artist_mbid UUID,
    source      VARCHAR(16) NOT NULL CHECK (source IN ('COMMONS', 'YOUTUBE', 'NONE')),
    s3_key      TEXT,
    url         TEXT,
    page_url    TEXT,
    author      TEXT,
    license     TEXT,
    checked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
