-- L2-3a LLM 제목 정리 (docs/l2-design.md)

-- LLM이 준 다른 언어 표기 (예: Through the Night ↔ 밤편지). 검색 색인에도 넣는다
ALTER TABLE title_alias ADD COLUMN query_title_alt TEXT;
-- 못 찾은 이유: RULE(규칙으로 못 풂), COVER·MEDLEY·NOT_SONG·INSTRUMENTAL(LLM 판정), INVALID(검증 실패), NOT_IN_MUSICBRAINZ
ALTER TABLE title_alias ADD COLUMN reason VARCHAR(32);

-- LLM 호출 기록 (사용자당 비용 계산용). 사용자와 연결하지 않는다
CREATE TABLE llm_call (
    id            BIGSERIAL   PRIMARY KEY,
    purpose       VARCHAR(32) NOT NULL,
    model         TEXT        NOT NULL,
    items         INTEGER     NOT NULL,
    input_tokens  INTEGER     NOT NULL,
    output_tokens INTEGER     NOT NULL,
    latency_ms    INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
