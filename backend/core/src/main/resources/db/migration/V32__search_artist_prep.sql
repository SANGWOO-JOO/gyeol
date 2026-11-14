-- 이름: V23의 artist_prepare(공용 지도 자리 준비)와 다른 일이라 search_artist_prep으로 둔다
-- 검색 때 가수 미리 준비 (11-14, B안). 방 검색 가수 카드가 우리 DB에 없는 가수(artistId 없음)거나 얼굴이 NONE이면
-- worker가 가수 확인 → 대표곡 3곡 식별·표지 → Commons 사진을 미리 해 둔다. 공용 지도에는 올리지 않는다.
-- prep_key: 'n:{정규화 이름}' 또는 'id:{artist_id}'. 같은 키는 하루 1번, 진행 중이면 다시 맡기지 않는다 (1시간 넘게 멈추면 다시)
CREATE TABLE search_artist_prep (
    prep_key     TEXT        PRIMARY KEY,
    name         TEXT        NOT NULL,
    status       VARCHAR(10) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'SKIPPED', 'FAILED')),
    artist_id    BIGINT      REFERENCES artist (id) ON DELETE SET NULL,
    reason       VARCHAR(32),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

-- 방당 하루 맡기기 수 (한 방이 가수를 연달아 검색해 큐를 채우지 않게)
CREATE TABLE search_artist_prep_daily (
    room_id BIGINT   NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    day     DATE     NOT NULL,
    count   SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (room_id, day)
);
