-- L3-4 추천과 이유 (docs/l3-design.md 11장). 세션 데이터라 30일 뒤 세션과 함께 지워진다

CREATE TABLE taste_profile (
    session_id  BIGINT      PRIMARY KEY REFERENCES session (id) ON DELETE CASCADE,
    embedding   vector(384),
    -- 곡 설명에서 가중 집계한 상위 장르·분위기·가수 [{name, weight}]
    facets      JSONB       NOT NULL,
    summary     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 후보 곡. 아직 식별되지 않은 Last.fm 후보는 track_id가 비어 있다
CREATE TABLE rec_candidate (
    session_id   BIGINT      NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    cand_key     TEXT        NOT NULL,
    title        TEXT        NOT NULL,
    artist       TEXT        NOT NULL,
    track_id     BIGINT      REFERENCES track (id),
    source       VARCHAR(16) NOT NULL CHECK (source IN ('LASTFM', 'VECTOR', 'BOTH')),
    lastfm_match REAL        NOT NULL DEFAULT 0,
    status       VARCHAR(8)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    PRIMARY KEY (session_id, cand_key)
);

CREATE TABLE recommendation (
    session_id BIGINT      NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    rank       SMALLINT    NOT NULL,
    track_id   BIGINT      NOT NULL REFERENCES track (id),
    score      REAL        NOT NULL,
    reason     TEXT,
    -- 이유에 인용한 지식 청크 ID와 바탕이 된 들은 곡 ID
    evidence   BIGINT[]    NOT NULL DEFAULT '{}',
    based_on   BIGINT[]    NOT NULL DEFAULT '{}',
    model      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, rank)
);
