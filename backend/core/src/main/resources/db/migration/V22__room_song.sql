-- 음악 방: 노래 담기 (docs/todo/room-api-db.md 1절 V22)

CREATE TABLE room_song (
    id        BIGSERIAL   PRIMARY KEY,
    room_id   BIGINT      NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    member_id BIGINT      NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    track_id  BIGINT      NOT NULL REFERENCES track (id),
    -- 지도·가수별 집계용으로 함께 저장 (track.artist_id와 같음)
    artist_id BIGINT      NOT NULL REFERENCES artist (id),
    added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (room_id, member_id, track_id)
);
CREATE INDEX idx_room_song_room_track ON room_song (room_id, track_id);
CREATE INDEX idx_room_song_room_artist ON room_song (room_id, artist_id);
CREATE INDEX idx_room_song_member ON room_song (member_id, added_at);

-- 우리 DB에 없는 곡(Last.fm 결과)을 식별하는 동안의 상태 (4-g 준비 중, 4-i 찾지 못함)
CREATE TABLE song_request (
    id          BIGSERIAL   PRIMARY KEY,
    room_id     BIGINT      NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    member_id   BIGINT      NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    title       TEXT        NOT NULL,
    artist      TEXT        NOT NULL,
    mbid        UUID,
    status      VARCHAR(12) NOT NULL DEFAULT 'IDENTIFYING' CHECK (status IN ('IDENTIFYING', 'DONE', 'FAILED')),
    track_id    BIGINT      REFERENCES track (id),
    room_song_id BIGINT,
    fail_reason VARCHAR(32),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_song_request_member_pending ON song_request (member_id) WHERE status = 'IDENTIFYING';

-- 하루 담기 한도 (사람·방당 100곡). 노래를 빼도 줄지 않게 따로 센다
CREATE TABLE member_daily (
    member_id   BIGINT   NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    day         DATE     NOT NULL,
    songs_added SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (member_id, day)
);
