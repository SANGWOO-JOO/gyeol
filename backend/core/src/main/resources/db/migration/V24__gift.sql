-- ④ 50곡 선물 (docs/todo/room-api-db.md 1절 V24, room-plan.md ⑯)
-- 최고 기록 기준: 빼고 다시 담아 같은 단계를 또 넘어도 상자를 만들지 않는다
CREATE TABLE member_progress (
    member_id      BIGINT  PRIMARY KEY REFERENCES member (id) ON DELETE CASCADE,
    best_count     INT     NOT NULL DEFAULT 0,
    next_milestone INT     NOT NULL DEFAULT 50
);

-- 상자. 하루 1개만 READY가 된다(ready_on, 한국 날짜). 넘친 상자는 WAITING으로 쌓였다가 다음 날 첫 조회 때 하나씩 READY
CREATE TABLE gift_box (
    id           BIGSERIAL   PRIMARY KEY,
    member_id    BIGINT      NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    milestone    INT         NOT NULL,
    status       VARCHAR(10) NOT NULL CHECK (status IN ('WAITING', 'READY', 'GENERATING', 'OPENED', 'FAILED')),
    earned_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_on     DATE,
    opened_at    TIMESTAMPTZ,
    character_id BIGINT,
    UNIQUE (member_id, milestone)
);
-- 하루 1개 보장 (동시 조회로 두 개가 READY가 되지 않게)
CREATE UNIQUE INDEX uq_gift_box_ready_day ON gift_box (member_id, ready_on) WHERE ready_on IS NOT NULL;

-- 받은 캐릭터. s3_key는 covers 버킷의 characters/…, 기본 캐릭터는 preset:N (API가 내장 그림을 준다)
CREATE TABLE gift_character (
    id          BIGSERIAL   PRIMARY KEY,
    member_id   BIGINT      NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    gift_box_id BIGINT      REFERENCES gift_box (id) ON DELETE SET NULL,
    s3_key      TEXT        NOT NULL,
    prompt      TEXT,
    model       TEXT        NOT NULL,
    is_fallback BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_gift_character_member ON gift_character (member_id);

ALTER TABLE gift_box ADD CONSTRAINT fk_gift_box_character FOREIGN KEY (character_id) REFERENCES gift_character (id) ON DELETE SET NULL;
-- V21에서 만들어 둔 열에 참조만 건다
ALTER TABLE member ADD CONSTRAINT fk_member_avatar FOREIGN KEY (avatar_character_id) REFERENCES gift_character (id) ON DELETE SET NULL;
