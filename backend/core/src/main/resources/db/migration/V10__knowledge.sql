-- L3-1 지식 인입 (docs/l3-design.md). 곡 설명(L3-2)은 이 청크만 근거로 쓴다. 전역 데이터, 90일마다 다시 가져온다

CREATE TABLE knowledge_chunk (
    id           BIGSERIAL   PRIMARY KEY,
    entity_type  VARCHAR(16) NOT NULL CHECK (entity_type IN ('TRACK', 'WORK', 'RELEASE_GROUP', 'ARTIST')),
    entity_mbid  UUID        NOT NULL,
    source       VARCHAR(16) NOT NULL CHECK (source IN ('MUSICBRAINZ', 'WIKIPEDIA', 'LASTFM')),
    lang         VARCHAR(8)  NOT NULL,
    url          TEXT        NOT NULL,
    license      VARCHAR(32) NOT NULL,
    text         TEXT        NOT NULL,
    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_type, entity_mbid, source, lang)
);

CREATE INDEX idx_knowledge_chunk_entity ON knowledge_chunk (entity_mbid);

-- 대상·출처별로 언제 가져왔는지 (없음도 기록해 매번 묻지 않는다)
CREATE TABLE knowledge_fetch (
    entity_type VARCHAR(16) NOT NULL,
    entity_mbid UUID        NOT NULL,
    status      VARCHAR(8)  NOT NULL CHECK (status IN ('OK', 'EMPTY')),
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (entity_type, entity_mbid)
);

-- 곡의 구조화된 크레딧 (MusicBrainz, CC0). L3-4의 "같은 작곡가·프로듀서 가산"에 쓴다
CREATE TABLE track_credit (
    track_id    BIGINT      NOT NULL REFERENCES track (id) ON DELETE CASCADE,
    role        VARCHAR(16) NOT NULL CHECK (role IN ('COMPOSER', 'LYRICIST', 'WRITER', 'ARRANGER', 'PRODUCER')),
    artist_mbid UUID,
    name        TEXT        NOT NULL,
    UNIQUE (track_id, role, name)
);

CREATE INDEX idx_track_credit_artist ON track_credit (artist_mbid);

-- 세션마다 보강할 곡 (재생 상위 50곡 + 고른 곡). 진행률은 이 행의 상태를 세어 계산한다
CREATE TABLE enrich_target (
    session_id BIGINT      NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    track_id   BIGINT      NOT NULL REFERENCES track (id),
    status     VARCHAR(8)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, track_id)
);

-- 곡이 어느 작품(work)의 녹음인지 (작곡·작사 크레딧과 곡 문서는 작품에 붙는다)
ALTER TABLE track ADD COLUMN work_mbids UUID[] NOT NULL DEFAULT '{}';
