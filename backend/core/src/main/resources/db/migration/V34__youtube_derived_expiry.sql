-- I-48 4-2 방안 A (11-15, 설계 docs/todo/youtube-derived-title-expiry.md): YouTube 카테고리 판정을 거쳐서만 들어온 일반 영상 제목이
-- 전역 테이블에 판정 뒤 29일(VideoCheckRepository.RETENTION)을 넘겨 남지 않게, 행마다 판정 시각을 둔다.
-- category_checked_at: 판정 경로로만 들어왔으면 그 판정 시각, 판정 없는 근거(음악 앱·Topic·방 담기)가 있으면 NULL(계속 보관).
-- 판정 경로 = listen_event.source 'YOUTUBE'이고 채널이 ' - Topic'이 아님 (web/src/takeout/filter.ts 불변식)
ALTER TABLE identify_target ADD COLUMN category_checked_at TIMESTAMPTZ;
ALTER TABLE title_alias     ADD COLUMN category_checked_at TIMESTAMPTZ;
ALTER TABLE title_guess     ADD COLUMN category_checked_at TIMESTAMPTZ;
ALTER TABLE medley_song     ADD COLUMN category_checked_at TIMESTAMPTZ;

CREATE INDEX idx_identify_target_category ON identify_target (category_checked_at) WHERE category_checked_at IS NOT NULL;
CREATE INDEX idx_title_alias_category     ON title_alias (category_checked_at)     WHERE category_checked_at IS NOT NULL;
CREATE INDEX idx_title_guess_category     ON title_guess (category_checked_at)     WHERE category_checked_at IS NOT NULL;
CREATE INDEX idx_medley_song_category     ON medley_song (category_checked_at)     WHERE category_checked_at IS NOT NULL;

-- 기존 행 채우기 (보수적으로: 근거를 찾을 수 없는 Topic 아닌 행은 판정분으로 본다)
-- 1) 식별 대상: 그 세션에 남은 재생으로. 재생이 없으면(7일 지나 지워짐) 출처·채널로, 시각은 대상 갱신 시각
UPDATE identify_target it SET category_checked_at = g.cat
FROM (
    SELECT l.session_id, l.title, l.channel,
           CASE WHEN bool_and(l.source = 'YOUTUBE' AND l.channel NOT LIKE '% - Topic') THEN coalesce(max(vc.checked_at), max(s.created_at)) END AS cat
    FROM listen_event l JOIN session s ON s.id = l.session_id LEFT JOIN video_check vc ON vc.video_id = l.video_id
    WHERE l.channel IS NOT NULL
    GROUP BY l.session_id, l.title, l.channel
) g
WHERE it.session_id = g.session_id AND it.title = g.title AND it.channel = g.channel;

UPDATE identify_target it SET category_checked_at = it.updated_at
WHERE it.category_checked_at IS NULL AND it.source = 'YOUTUBE' AND it.channel IS NOT NULL AND it.channel NOT LIKE '% - Topic'
  AND NOT EXISTS (SELECT 1 FROM listen_event l WHERE l.session_id = it.session_id);

-- 2) 별칭: Topic 키(방 담기 포함)거나 판정 없는 대상이 같은 키를 쓰면 NULL, 판정 대상이 있으면 가장 늦은 판정 시각, 근거가 없으면 확인 시각
UPDATE title_alias a SET category_checked_at = x.cat
FROM (
    SELECT a2.alias_key,
           CASE
               WHEN a2.alias_key LIKE '%- topic' THEN NULL
               WHEN EXISTS (SELECT 1 FROM identify_target it WHERE it.alias_key = a2.alias_key AND it.category_checked_at IS NULL) THEN NULL
               ELSE coalesce((SELECT max(it.category_checked_at) FROM identify_target it WHERE it.alias_key = a2.alias_key), a2.checked_at)
           END AS cat
    FROM title_alias a2
) x
WHERE a.alias_key = x.alias_key;

-- 3) 추측·메들리: 남은 재생으로 (판정 없는 재생이 있으면 NULL). 재생 근거가 없으면 추측은 확인 시각, 메들리는 지금
WITH g AS (
    SELECT l.title, l.channel,
           CASE WHEN bool_and(l.source = 'YOUTUBE' AND l.channel NOT LIKE '% - Topic') THEN coalesce(max(vc.checked_at), max(s.created_at)) END AS cat
    FROM listen_event l JOIN session s ON s.id = l.session_id LEFT JOIN video_check vc ON vc.video_id = l.video_id
    WHERE l.channel IS NOT NULL
    GROUP BY l.title, l.channel
)
UPDATE title_guess t SET category_checked_at = CASE WHEN g.title IS NULL THEN t.checked_at ELSE g.cat END
FROM title_guess t2 LEFT JOIN g ON g.title = t2.title AND g.channel = t2.channel
WHERE t.title = t2.title AND t.channel = t2.channel;

WITH g AS (
    SELECT l.title, l.channel,
           CASE WHEN bool_and(l.source = 'YOUTUBE' AND l.channel NOT LIKE '% - Topic') THEN coalesce(max(vc.checked_at), max(s.created_at)) END AS cat
    FROM listen_event l JOIN session s ON s.id = l.session_id LEFT JOIN video_check vc ON vc.video_id = l.video_id
    WHERE l.channel IS NOT NULL
    GROUP BY l.title, l.channel
)
UPDATE medley_song m SET category_checked_at = CASE WHEN g.title IS NULL THEN now() ELSE g.cat END
FROM medley_song m2 LEFT JOIN g ON g.title = m2.title AND g.channel = m2.channel
WHERE m.title = m2.title AND m.channel = m2.channel AND m.position = m2.position;
