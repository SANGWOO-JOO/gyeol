-- I-30 ③ 가수 화면 최신순 (docs/todo/artist-search.md 4절). 전역 데이터: 사용자·방과 연결 없음 (artist·track과 같은 성격).
-- track에 넣지 않는다: track은 "식별된 곡"이라 검색어·지도·추천이 붙는다. 녹음 목록을 넣으면 검색이 흐려진다
CREATE TABLE artist_release_scan (
    artist_id    BIGINT      PRIMARY KEY REFERENCES artist (id) ON DELETE CASCADE,
    status       VARCHAR(10) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    -- MusicBrainz가 알려 준 전체 녹음 수와 저장한 곡 수 (같은 제목은 하나로)
    recordings   INT,
    stored       INT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

-- 가수의 곡 하나 = 같은 제목(정규화) 중 가장 이른 날짜의 녹음
CREATE TABLE artist_recording (
    artist_id          BIGINT      NOT NULL REFERENCES artist (id) ON DELETE CASCADE,
    recording_mbid     UUID        NOT NULL,
    title              TEXT        NOT NULL,
    title_norm         TEXT        NOT NULL,
    -- YYYY, YYYY-MM, YYYY-MM-DD (MusicBrainz first-release-date). 없으면 NULL → 화면 "발매일 모름"
    first_release_date VARCHAR(10),
    -- Live·Inst.·Remix 같은 판 표시가 제목에 있음 (기본으로 숨김)
    is_version         BOOLEAN     NOT NULL DEFAULT false,
    PRIMARY KEY (artist_id, recording_mbid),
    UNIQUE (artist_id, title_norm)
);
CREATE INDEX idx_artist_recording_latest ON artist_recording (artist_id, is_version, first_release_date DESC NULLS LAST);
