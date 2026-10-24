-- Last.fm 검색 결과에는 MusicBrainz ID가 비어 있는 경우가 많다 (특히 한국 곡).
-- 고른 곡의 mbid는 선택 값으로 바꾸고, 정확한 ID는 L2에서 MusicBrainz로 채운다.
ALTER TABLE seed_track ALTER COLUMN mbid DROP NOT NULL;
ALTER TABLE seed_track DROP CONSTRAINT seed_track_session_id_mbid_key;

-- 같은 세션에 같은 곡(제목·가수, 대소문자 무시)을 두 번 고를 수 없다
CREATE UNIQUE INDEX ux_seed_track_session_title_artist ON seed_track (session_id, lower(title), lower(artist));
