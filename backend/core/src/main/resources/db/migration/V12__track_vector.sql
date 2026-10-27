-- L3-3 곡 설명 임베딩 (intfloat/multilingual-e5-small, 384차원, "query:" 접두어). L3-4 벡터 이웃 후보에 쓴다
CREATE TABLE track_vector (
    track_id   BIGINT      PRIMARY KEY REFERENCES track (id) ON DELETE CASCADE,
    model      TEXT        NOT NULL,
    embedding  vector(384) NOT NULL,
    -- 임베딩한 문장의 해시. 곡 설명 문장이 바뀌지 않았으면 다시 만들지 않는다
    text_hash  VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_track_vector_hnsw ON track_vector USING hnsw (embedding vector_cosine_ops);
