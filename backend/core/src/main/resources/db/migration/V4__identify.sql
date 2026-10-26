-- L2-1 곡 식별 (docs/l2-design.md). artist·track·title_alias는 사용자와 연결되지 않는 전역 데이터로 계속 보관한다.

CREATE TABLE artist (
    id        BIGSERIAL PRIMARY KEY,
    mbid      UUID   NOT NULL UNIQUE,
    name      TEXT   NOT NULL,
    sort_name TEXT,
    -- 한·영·일 별칭 (검색·표시용)
    aliases   TEXT[] NOT NULL DEFAULT '{}'
);

CREATE TABLE track (
    id                 BIGSERIAL PRIMARY KEY,
    recording_mbid     UUID   NOT NULL UNIQUE,
    title              TEXT   NOT NULL,
    artist_id          BIGINT NOT NULL REFERENCES artist (id),
    -- 원곡이 처음 실린 공식 음반 (docs/l1-design.md 4-3)
    release_mbid       UUID,
    release_group_mbid UUID,
    release_title      TEXT,
    release_date       VARCHAR(10),
    release_type       VARCHAR(32),
    -- 표지 주소는 L2-2에서 채운다
    cover_url          TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 전역 캐시: 정규화한 "제목|채널" → 곡. 못 찾은 것도 기록해 다시 묻지 않는다 (30일 뒤 다시 시도)
CREATE TABLE title_alias (
    alias_key    TEXT        PRIMARY KEY,
    track_id     BIGINT      REFERENCES track (id),
    status       VARCHAR(16) NOT NULL CHECK (status IN ('MATCHED', 'NOT_FOUND')),
    method       VARCHAR(16) NOT NULL CHECK (method IN ('RULE', 'LLM', 'MANUAL')),
    query_artist TEXT,
    query_title  TEXT,
    checked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'MATCHED') = (track_id IS NOT NULL))
);

-- 세션마다 바로 식별할 대상 (재생 상위 N곡). 진행률은 이 행의 상태를 세어 계산한다
CREATE TABLE identify_target (
    session_id BIGINT      NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    alias_key  TEXT        NOT NULL,
    title      TEXT        NOT NULL,
    channel    TEXT,
    source     VARCHAR(16) NOT NULL,
    plays      INTEGER     NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, alias_key)
);

CREATE INDEX idx_identify_target_pending ON identify_target (session_id) WHERE status = 'PENDING';

ALTER TABLE listen_event ADD CONSTRAINT fk_listen_event_track FOREIGN KEY (track_id) REFERENCES track (id);
CREATE INDEX idx_listen_event_track_id ON listen_event (track_id);
