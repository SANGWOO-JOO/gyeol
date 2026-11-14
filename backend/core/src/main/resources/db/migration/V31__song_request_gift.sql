-- I-51 (11-14): 식별(202)을 거쳐 담긴 곡이 선물 상자를 만들었을 때, 요청 결과(GET song-requests/{id})에도 상자를 알려 준다.
-- 곧바로 담기(201) 응답의 gift와 같은 모양. 상태는 상자가 생긴 순간의 값(WAITING·READY)을 남긴다 (화면 알림용, 지금 상태는 내 정보로 본다).
-- 상자가 지워지면(방 완전 삭제 등) 함께 비운다.
ALTER TABLE song_request
    ADD COLUMN gift_box_id BIGINT REFERENCES gift_box (id) ON DELETE SET NULL,
    ADD COLUMN gift_status VARCHAR(16);
