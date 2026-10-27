-- L3-2 곡 설명 (docs/l3-design.md). 지식 청크만 근거로 LLM이 만든다. 전역 캐시, 지식이 바뀌면 다시 만든다
CREATE TABLE track_descriptor (
    track_id   BIGINT      PRIMARY KEY REFERENCES track (id) ON DELETE CASCADE,
    lang       VARCHAR(8)  NOT NULL,
    summary    TEXT,
    -- 칸마다 {value, evidence: [청크 ID], level: TRACK|RELEASE|ARTIST|NONE, confidence: HIGH|MEDIUM|LOW|NONE}
    fields     JSONB       NOT NULL,
    model      TEXT        NOT NULL,
    rag        BOOLEAN     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
