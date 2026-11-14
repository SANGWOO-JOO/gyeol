-- 11-14: MusicBrainz begin은 Person(Character)이면 출생일이다. 가수 사실 조각에서 사람의 생일을 "활동 시작"으로 쓴 부분을 지운다.
-- 새로 받는 조각은 fetcher(KnowledgeService.artist)가 이미 넣지 않는다. Group의 결성일("활동 시작")은 그대로 둔다.
-- 연도를 모르는 "????-12-02" 꼴도 지운다. 멱등: 다시 돌려도 바꿀 행이 없다.
-- 번호: I-48 제목 만료(docs/todo/youtube-derived-title-expiry.md)는 V34로 미뤘다 (Flyway outOfOrder가 꺼져 있어 V34를 먼저 두면 나중의 V33이 검증에서 막힌다)
UPDATE knowledge_chunk
SET text = regexp_replace(text, '; 활동 시작 [0-9?-]+', '', 'g')
WHERE entity_type = 'ARTIST'
  AND source = 'MUSICBRAINZ'
  AND text ~ '유형 (Person|Character);'
  AND text ~ '; 활동 시작 [0-9?-]+';
