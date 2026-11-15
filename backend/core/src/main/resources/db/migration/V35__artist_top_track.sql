-- I-72 B-1·B-2 (11-15): 검색 때 가수 미리 준비를 인기곡 목록 + 준비 커서로.
-- artist_top_track: 가수 키(search_artist_prep.prep_key와 같음, 'n:{정규화 이름}' 또는 'id:{artist_id}')별 Last.fm 인기곡 순위.
-- 7일 지나면 다시 받는다(fetched_at). 순위마다 준비 상태: PENDING(아직) READY(우리 곡·표지 연결) NO_MATCH(MusicBrainz에 없음)
-- OTHER(다른 가수 곡으로 식별) FAILED(호출 실패). 실패·없음도 tried_at을 남겨 며칠 뒤 다시 시도한다
CREATE TABLE artist_top_track (
    prep_key   TEXT        NOT NULL,
    rank       SMALLINT    NOT NULL,
    title      TEXT        NOT NULL,
    track_id   BIGINT      REFERENCES track (id) ON DELETE SET NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'READY', 'NO_MATCH', 'OTHER', 'FAILED')),
    tried_at   TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (prep_key, rank)
);

-- 준비 커서: 첫 검색은 1~10위, 더보기(2쪽 이후)는 1~20위까지. 하루에 돌 수 있는 묶음(3곡) 수를 센다
ALTER TABLE search_artist_prep
    ADD COLUMN window_end  SMALLINT NOT NULL DEFAULT 10,
    ADD COLUMN batches_day DATE,
    ADD COLUMN batches     SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN artist_mbid UUID;
