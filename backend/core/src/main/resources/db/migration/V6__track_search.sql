-- L2-4 우리 DB 곡 검색 (docs/l2-design.md). 한 곡에 여러 표기(MusicBrainz 곡명, YouTube 제목, 가수 이름·별칭, 가수+곡 조합)를 넣는다.
-- 정규화(NFKC·소문자·공백과 문장부호 제거)와 초성 추출은 앱(core SearchText)이 한다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE track_search_term (
    id       BIGSERIAL   PRIMARY KEY,
    track_id BIGINT      NOT NULL REFERENCES track (id) ON DELETE CASCADE,
    term     TEXT        NOT NULL,
    -- 한글 초성만 ("아이유밤편지" → "ㅇㅇㅇㅂㅍㅈ"). 한글이 없으면 빈 문자열
    chosung  TEXT        NOT NULL,
    kind     VARCHAR(16) NOT NULL CHECK (kind IN ('TITLE', 'ARTIST', 'ARTIST_TITLE')),
    UNIQUE (track_id, term)
);

CREATE INDEX idx_track_search_term_trgm ON track_search_term USING gin (term gin_trgm_ops);
CREATE INDEX idx_track_search_chosung_trgm ON track_search_term USING gin (chosung gin_trgm_ops);

-- 이 곡에 연결된 재생 수 누적 (같은 점수면 많이 들린 곡을 먼저)
ALTER TABLE track ADD COLUMN plays_total BIGINT NOT NULL DEFAULT 0;
