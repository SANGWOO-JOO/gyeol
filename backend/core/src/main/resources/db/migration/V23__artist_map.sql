-- 음악 지도 (모든 방 공용, docs/todo/room-api-db.md 1절 V23, room-plan.md 8절)

-- 가수 단위 설명 (RAG) + 초기 가수 표시
CREATE TABLE artist_profile (
    artist_id    BIGINT      PRIMARY KEY REFERENCES artist (id) ON DELETE CASCADE,
    -- MusicBrainz 출신 국가 (ISO 2자리). 모르면 NULL
    country      VARCHAR(2),
    -- 초기 지도 가수면 Last.fm 나라별 순위에서의 자리 (방에서 새로 들어온 가수는 NULL)
    seed_country VARCHAR(2),
    seed_rank    INT,
    description  TEXT,
    -- 설명에 쓴 근거 조각 id (knowledge_chunk)
    evidence     BIGINT[]    NOT NULL DEFAULT '{}',
    model        TEXT,
    described_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_artist_profile_seed ON artist_profile (seed_country, seed_rank) WHERE seed_country IS NOT NULL;

-- 가수 벡터: 근거 원문(나라 단어 제거)과 설명을 0.5 : 0.5로 섞은 것이 지도 기준 (11-11 측정)
CREATE TABLE artist_vector (
    artist_id  BIGINT      PRIMARY KEY REFERENCES artist (id) ON DELETE CASCADE,
    model      TEXT        NOT NULL,
    raw        vector(384) NOT NULL,
    descr      vector(384),
    mixed      vector(384) NOT NULL,
    text_hash  VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_artist_vector_hnsw ON artist_vector USING hnsw (mixed vector_cosine_ops);

-- 지도 판. 좌표는 판마다 고정, 화면은 active 판 하나만 쓴다
CREATE TABLE map_version (
    id           BIGSERIAL   PRIMARY KEY,
    params       JSONB       NOT NULL,
    artist_count INT         NOT NULL,
    active       BOOLEAN     NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_map_version_active ON map_version (active) WHERE active;

-- 구역 (가까운 가수 묶음). 이름은 LLM이 소속 가수 설명을 근거로 붙인다
CREATE TABLE galaxy (
    id          BIGSERIAL   PRIMARY KEY,
    map_version BIGINT      NOT NULL REFERENCES map_version (id) ON DELETE CASCADE,
    label       INT         NOT NULL,
    name        TEXT,
    reason      TEXT,
    evidence    BIGINT[]    NOT NULL DEFAULT '{}',
    cx          DOUBLE PRECISION NOT NULL,
    cy          DOUBLE PRECISION NOT NULL,
    UNIQUE (map_version, label)
);

-- 좌표 (0~1). UMAP으로 만든 가수와, 나중에 이웃 평균으로 자리 잡은 가수
CREATE TABLE artist_position (
    map_version BIGINT      NOT NULL REFERENCES map_version (id) ON DELETE CASCADE,
    artist_id   BIGINT      NOT NULL REFERENCES artist (id) ON DELETE CASCADE,
    x           DOUBLE PRECISION NOT NULL,
    y           DOUBLE PRECISION NOT NULL,
    galaxy_id   BIGINT      REFERENCES galaxy (id) ON DELETE SET NULL,
    placed_by   VARCHAR(8)  NOT NULL CHECK (placed_by IN ('UMAP', 'NEIGHBOR')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (map_version, artist_id)
);

-- 지도에 없는 가수를 올리는 작업 (방에서 처음 담긴 가수). 4-g "준비 중"
CREATE TABLE artist_prepare (
    artist_id    BIGINT      PRIMARY KEY REFERENCES artist (id) ON DELETE CASCADE,
    status       VARCHAR(8)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    step         VARCHAR(16),
    error        TEXT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at      TIMESTAMPTZ
);
