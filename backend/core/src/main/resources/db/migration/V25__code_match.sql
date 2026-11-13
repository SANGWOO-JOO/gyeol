-- ⑤ 놀이터 · 코드 맞추기 (docs/todo/room-api-db.md 1절 V25, room-plan.md ⑰)
-- 점수는 API가 SQL 재료로 바로 계산, 이유는 worker가 근거(가수 RAG 설명) 번호와 함께 쓴다.
-- 두 사람의 곡 목록·지도 판이 그대로면(input_hash 같음) 저장 결과를 다시 쓴다
CREATE TABLE code_match (
    id          BIGSERIAL   PRIMARY KEY,
    room_id     BIGINT      NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    member_a    BIGINT      NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    member_b    BIGINT      NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    input_hash  CHAR(64)    NOT NULL,
    score       SMALLINT    NOT NULL CHECK (score BETWEEN 0 AND 100),
    -- 항목별 0~1 점수와 재료(겹친 가수·구역 등)
    parts       JSONB       NOT NULL,
    reason      TEXT,
    evidence    BIGINT[]    NOT NULL DEFAULT '{}',
    status      VARCHAR(10) NOT NULL CHECK (status IN ('REASONING', 'DONE', 'NO_REASON')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (member_a < member_b),
    UNIQUE (room_id, member_a, member_b, input_hash)
);
CREATE INDEX idx_code_match_pair ON code_match (room_id, member_a, member_b, created_at DESC);
-- 방당 하루 새 계산 수를 센다
CREATE INDEX idx_code_match_room_day ON code_match (room_id, created_at);
