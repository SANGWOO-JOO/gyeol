-- L2-2 앨범 표지와 고른 곡 식별 (docs/l2-design.md)

-- 표지 주소(Cover Art Archive, 이미지는 저장하지 않음)와 확인 시각. 주소가 비어 있고 시각만 있으면 "표지 없음" (30일 뒤 다시 확인)
ALTER TABLE track ADD COLUMN cover_checked_at TIMESTAMPTZ;

-- A 입구에서 고른 곡도 식별해 곡에 연결한다
ALTER TABLE seed_track ADD COLUMN track_id BIGINT REFERENCES track (id);
