-- 가수 이미지 직접 지정 (docs/todo/artist-images.md). 자동 선택이 맞지 않는 가수만 적는다.
-- skip_commons: Wikidata 대표 사진이 가수를 나타내지 않으면 Commons를 건너뛰고 YouTube 프로필로 넘긴다
CREATE TABLE artist_image_rule (
    artist_mbid  UUID    PRIMARY KEY,
    skip_commons BOOLEAN NOT NULL DEFAULT false,
    note         TEXT    NOT NULL
);

-- ZARD: Commons 관련 사진이 2007년 추모 공간 사진뿐이고 본인 사진이 없다 (2026-11-10 확인). YouTube 프로필은 공식 로고
INSERT INTO artist_image_rule (artist_mbid, skip_commons, note)
VALUES ('b72cbca4-c004-4c62-8223-8314fd1a532e', true, 'ZARD: Commons 대표 사진이 추모 공간 사진, YouTube 공식 로고 사용');

-- 이미 저장된 ZARD의 Commons 결과는 다시 찾게 한다
DELETE FROM artist_image ai USING artist a WHERE a.id = ai.artist_id AND a.mbid = 'b72cbca4-c004-4c62-8223-8314fd1a532e';
