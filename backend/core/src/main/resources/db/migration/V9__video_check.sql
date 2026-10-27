-- L2-3b 일반 영상 음악 판정 캐시 (YouTube Data API videos.list의 categoryId).
-- YouTube API 정책에 따라 30일이 지나면 정리 작업이 지운다. 제목은 저장하지 않는다
CREATE TABLE video_check (
    video_id    VARCHAR(32) PRIMARY KEY,
    -- 비어 있으면 영상을 찾지 못함 (삭제·비공개)
    category_id VARCHAR(8),
    checked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_video_check_checked_at ON video_check (checked_at);
