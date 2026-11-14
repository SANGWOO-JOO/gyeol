-- I-45 (11-14): YouTube 채널 프로필은 가수 이미지로 쓰지 않는다 (가수 사진이 아닌 경우가 많고 저작자 표시가 없다).
-- 받아 둔 YOUTUBE 행은 NONE으로 바꾸지 않고 지운다: 미확인 상태가 되어 다음 세션 계획·지도 일괄 채우기에서 Commons만 다시 찾는다.
-- (NONE으로 두면 30일 재확인 기한까지 Commons를 다시 보지 않는다.) CHECK 제약의 'YOUTUBE' 값은 옛 행 호환을 위해 그대로 둔다.
DELETE FROM artist_image WHERE source = 'YOUTUBE';
DELETE FROM channel_image WHERE source = 'YOUTUBE';
