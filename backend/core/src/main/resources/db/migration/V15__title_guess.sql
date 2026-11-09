-- 방송·일반 채널 영상 제목에서 LLM이 뽑은 가수·곡 (docs/todo/analysis-latency-errors.md).
-- MusicBrainz 원곡을 확인한 것이 아니므로 title_alias와 나눈다. 사용자와 연결되지 않는 전역 캐시.
CREATE TABLE title_guess (
    title      TEXT        NOT NULL,
    channel    TEXT        NOT NULL,
    artist     TEXT,
    artist_alt TEXT,
    song       TEXT,
    song_alt   TEXT,
    version    VARCHAR(16) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (title, channel)
);
