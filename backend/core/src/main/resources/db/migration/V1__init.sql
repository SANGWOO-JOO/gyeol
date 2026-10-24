-- L1 첫 마이그레이션: 세션, 업로드, 청취 기록, 고른 곡
-- 벡터 기능은 L3에서 쓰지만 확장은 지금 켜 둔다 (RDS에서도 같은 확장을 쓴다).
CREATE EXTENSION IF NOT EXISTS vector;

-- 결과 링크 하나 = 세션 하나. 링크의 토큰 원문은 저장하지 않고 해시만 저장한다.
CREATE TABLE session (
    id                 BIGSERIAL PRIMARY KEY,
    token_hash         BYTEA       NOT NULL UNIQUE,
    entry              VARCHAR(8)  NOT NULL CHECK (entry IN ('SEEDS', 'TAKEOUT')),
    status             VARCHAR(16) NOT NULL DEFAULT 'CREATED'
                       CHECK (status IN ('CREATED', 'UPLOADING', 'PROCESSING', 'DONE', 'FAILED')),
    -- 브라우저 필터 결과 (전체 항목 / 음악으로 골라낸 것 / 미판정)
    total_count        INTEGER,
    music_count        INTEGER,
    unclassified_count INTEGER,
    -- 워커가 실제로 저장한 건수와 분석 기간
    saved_count        INTEGER,
    period_start       TIMESTAMPTZ,
    period_end         TIMESTAMPTZ,
    error_message      TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_session_expires_at ON session (expires_at);

-- S3에 올라온 파일 하나 = 업로드 하나. 같은 파일 메시지가 두 번 와도 한 번만 처리하려고 s3_key에 고유 제약을 둔다.
CREATE TABLE upload (
    id           UUID        PRIMARY KEY,
    session_id   BIGINT      NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    s3_key       TEXT        NOT NULL UNIQUE,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    item_count   INTEGER,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX idx_upload_session_id ON upload (session_id);

-- 브라우저 필터를 통과한 음악 항목. 일반 영상 제목은 여기 오지 않는다.
CREATE TABLE listen_event (
    id         BIGSERIAL   PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    upload_id  UUID        NOT NULL REFERENCES upload (id) ON DELETE CASCADE,
    video_id   VARCHAR(32),
    title      TEXT        NOT NULL,
    channel    TEXT,
    watched_at TIMESTAMPTZ NOT NULL,
    source     VARCHAR(16) NOT NULL CHECK (source IN ('MUSIC_APP', 'YOUTUBE')),
    -- L2에서 곡 식별 결과를 채운다
    track_id   BIGINT,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_listen_event_session_id ON listen_event (session_id);
CREATE INDEX idx_listen_event_expires_at ON listen_event (expires_at);

-- A 입구에서 고른 곡 (3~5개)
CREATE TABLE seed_track (
    id         BIGSERIAL    PRIMARY KEY,
    session_id BIGINT       NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    mbid       UUID         NOT NULL,
    title      TEXT         NOT NULL,
    artist     TEXT         NOT NULL,
    position   SMALLINT     NOT NULL,
    UNIQUE (session_id, mbid)
);
