-- I-34 기존 데이터 정리 (LLM 다시 부르지 않음). 저장 코드(Citations)와 같은 규칙:
-- 가수 설명 본문의 근거 번호 "[564][565]"(번호는 evidence 칸에 있음), Last.fm 근거 조각의 위키 각주 "[1][2]"
UPDATE artist_profile
SET description = btrim(regexp_replace(regexp_replace(description, '\s*\[\d+\]', '', 'g'), '\s+([.,!?;:)])', '\1', 'g'))
WHERE description ~ '\[\d+\]';

UPDATE knowledge_chunk
SET text = btrim(regexp_replace(regexp_replace(text, '\s*\[\d{1,3}\]', '', 'g'), '\s+([.,!?;:)])', '\1', 'g'))
WHERE text ~ '\[\d{1,3}\]';
