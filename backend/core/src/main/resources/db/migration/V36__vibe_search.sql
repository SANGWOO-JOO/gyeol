-- 분위기로 가수 찾기 · Naive RAG (docs/todo/vibe-search-rag.md 5절. 설계 당시 이름 V28은 이미 쓰여 V36)
-- api가 요청을 저장(PENDING) → worker가 질문 임베딩 → 가수 벡터 top-10 → LLM이 3~5명 고르고 근거 번호와 이유 → DONE.
-- 같은 방에서 같은 질문(정규화 해시)·같은 지도 판이면 저장 결과를 다시 쓴다
CREATE TABLE vibe_search (
    id          BIGSERIAL   PRIMARY KEY,
    room_id     BIGINT      NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    member_id   BIGINT      NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    query       TEXT        NOT NULL,
    query_hash  VARCHAR(64) NOT NULL,
    -- 활성 지도 판 (없으면 0)
    map_version BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    -- 검색 top-10 [{artistId, d}] (코사인 거리)
    hits        JSONB,
    -- 고른 가수 [{artistId, reason, evidence}]. DONE인데 비었으면 가까운 가수 없음(notFound)
    picks       JSONB,
    evidence    BIGINT[]    NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_vibe_search_reuse ON vibe_search (room_id, query_hash, map_version, created_at DESC);
-- 멤버당 하루 새 요청 수를 센다
CREATE INDEX idx_vibe_search_member_day ON vibe_search (member_id, created_at);

-- D1 기본값 descr: 설명 벡터 검색용 HNSW (mixed는 V23에 이미 있음)
CREATE INDEX idx_artist_vector_descr_hnsw ON artist_vector USING hnsw (descr vector_cosine_ops);
