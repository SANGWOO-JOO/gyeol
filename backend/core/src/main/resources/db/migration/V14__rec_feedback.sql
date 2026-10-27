-- L3-5 추천 피드백 (docs/l3-design.md 12장)

-- 이유를 근거 자료와 함께(RAG) 썼는지, 근거 없이(NORAG) 썼는지. 한 세션 20곡 중 10곡씩 블라인드로 섞는다
ALTER TABLE recommendation
    ADD COLUMN reason_variant VARCHAR(6) NOT NULL DEFAULT 'RAG' CHECK (reason_variant IN ('RAG', 'NORAG')),
    -- LLM 재정렬 전 점수 순위와 후보 경로 (가중치 조정 집계용)
    ADD COLUMN score_rank     SMALLINT,
    ADD COLUMN source         VARCHAR(16);

-- 세션이 지워져도 집계용으로 남긴다 (session_id만 비운다). 곡·점수·비교군만 있고 들은 기록은 없다
CREATE TABLE rec_feedback (
    id             BIGSERIAL   PRIMARY KEY,
    session_id     BIGINT      REFERENCES session (id) ON DELETE SET NULL,
    track_id       BIGINT      NOT NULL REFERENCES track (id),
    rating         VARCHAR(8)  NOT NULL CHECK (rating IN ('LIKE', 'DISLIKE')),
    reason_wrong   BOOLEAN     NOT NULL DEFAULT false,
    -- 평가할 때의 추천 정보 (세션이 지워져도 집계할 수 있게 복사)
    reason_variant VARCHAR(6)  NOT NULL,
    rank           SMALLINT    NOT NULL,
    score_rank     SMALLINT,
    score          REAL        NOT NULL,
    source         VARCHAR(16),
    model          TEXT        NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, track_id)
);
