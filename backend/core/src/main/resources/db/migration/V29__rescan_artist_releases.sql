-- I-39·I-41: 최신순 수집 규칙이 바뀌었다(주 가수가 다른 녹음·60초 미만·팬 편집·라이브 사이 부분 제외).
-- 이미 모은 가수 목록을 비워, 다음에 가수 화면을 열 때 새 규칙으로 다시 모은다 (전역 캐시 성격의 데이터라 지워도 됨)
DELETE FROM artist_recording;
DELETE FROM artist_release_scan;
