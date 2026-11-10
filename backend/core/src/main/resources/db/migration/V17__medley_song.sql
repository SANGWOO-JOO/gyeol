-- 메들리 영상에 든 곡 (LLM이 제목에서 뽑음). 메들리를 재생하면 든 곡을 모두 들은 것으로 본다.
-- track_id는 MusicBrainz로 찾은 곡. 비어 있으면 통계가 세션의 확인된 곡과 이름으로 맞춘다. 사용자와 연결되지 않는 전역 캐시.
CREATE TABLE medley_song (
    title    TEXT    NOT NULL,
    channel  TEXT    NOT NULL,
    position INTEGER NOT NULL,
    artist   TEXT,
    song     TEXT    NOT NULL,
    track_id BIGINT REFERENCES track (id) ON DELETE SET NULL,
    PRIMARY KEY (title, channel, position)
);
