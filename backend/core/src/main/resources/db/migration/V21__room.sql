-- 음악 방: 방·참가자·기기 열쇠·방 만들기 인증 (docs/todo/room-api-db.md 1절 V21)

CREATE TABLE room (
    id             BIGSERIAL   PRIMARY KEY,
    -- 링크에 쓰는 무작위 문자열 (숫자 id를 드러내지 않는다)
    public_id      VARCHAR(16) NOT NULL UNIQUE,
    name           TEXT        NOT NULL,
    -- ACTIVE: 참가 가능, CLOSED: 참가 마감(기존 참가자는 그대로), HIDDEN: 삭제(숨김, purge_at에 완전 삭제)
    status         VARCHAR(8)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED', 'HIDDEN')),
    -- 지도가 바뀔 때마다 +1. 지도 새로고침의 ETag
    map_rev        BIGINT      NOT NULL DEFAULT 0,
    hidden_at      TIMESTAMPTZ,
    purge_at       TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_room_purge_at ON room (purge_at) WHERE purge_at IS NOT NULL;

-- 방장 이메일 (방 복구용). 방이 완전히 지워질 때 함께 지워진다
CREATE TABLE room_host_email (
    room_id    BIGINT      PRIMARY KEY REFERENCES room (id) ON DELETE CASCADE,
    email      TEXT        NOT NULL,
    consent_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE member (
    id                  BIGSERIAL   PRIMARY KEY,
    room_id             BIGINT      NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    nickname            VARCHAR(12) NOT NULL,
    role                VARCHAR(8)  NOT NULL CHECK (role IN ('HOST', 'MEMBER')),
    -- 사람 색 (0 방장 라벤더, 1 로즈, 2~4). 빈 번호 중 가장 작은 것
    color_idx           SMALLINT    NOT NULL,
    -- 4자리 암호의 BCrypt 해시. 원문은 저장·로그 출력하지 않는다
    pin_hash            TEXT        NOT NULL,
    fail_count          SMALLINT    NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    avatar_character_id BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at          TIMESTAMPTZ
);
-- 방 안 닉네임 중복 금지 (대소문자 무시, 내보낸 사람 제외)
CREATE UNIQUE INDEX ux_member_room_nickname ON member (room_id, lower(nickname)) WHERE removed_at IS NULL;
CREATE INDEX idx_member_room ON member (room_id) WHERE removed_at IS NULL;

-- 기기 열쇠. 원문은 쿠키·localStorage에만, 여기에는 SHA-256 해시만
CREATE TABLE member_key (
    key_hash     BYTEA       PRIMARY KEY,
    member_id    BIGINT      NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX idx_member_key_member ON member_key (member_id);

-- 방 만들기: 이메일 인증 전 내용을 잠시 보관 (1시간)
CREATE TABLE room_draft (
    id         BIGSERIAL   PRIMARY KEY,
    -- 브라우저에 주는 draft 토큰의 해시 (숫자 id로 남의 draft를 건드리지 못하게)
    token_hash BYTEA       NOT NULL UNIQUE,
    name       TEXT        NOT NULL,
    nickname   VARCHAR(12) NOT NULL,
    pin_hash   TEXT        NOT NULL,
    email      TEXT        NOT NULL,
    consent_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_room_draft_expires ON room_draft (expires_at);

-- 메일 6자리 코드 (방 만들기 인증, 방장 암호 재설정)
CREATE TABLE email_code (
    id           BIGSERIAL   PRIMARY KEY,
    purpose      VARCHAR(16) NOT NULL CHECK (purpose IN ('CREATE', 'HOST_RESET')),
    draft_id     BIGINT      REFERENCES room_draft (id) ON DELETE CASCADE,
    room_id      BIGINT      REFERENCES room (id) ON DELETE CASCADE,
    code_hash    TEXT        NOT NULL,
    attempts     SMALLINT    NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ NOT NULL,
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at      TIMESTAMPTZ,
    CHECK ((draft_id IS NULL) <> (room_id IS NULL))
);
CREATE INDEX idx_email_code_draft ON email_code (draft_id);
CREATE INDEX idx_email_code_room ON email_code (room_id);

-- 이메일별·IP별 발송 제한. 이메일은 해시로만
CREATE TABLE mail_send_log (
    id         BIGSERIAL   PRIMARY KEY,
    email_hash BYTEA       NOT NULL,
    ip         VARCHAR(64),
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mail_send_log_email ON mail_send_log (email_hash, sent_at);
CREATE INDEX idx_mail_send_log_ip ON mail_send_log (ip, sent_at);

-- 누가·언제·무엇을 (관리자 상세 화면, 방장 행동 포함)
CREATE TABLE audit_log (
    id               BIGSERIAL   PRIMARY KEY,
    actor_type       VARCHAR(8)  NOT NULL CHECK (actor_type IN ('ADMIN', 'HOST', 'SYSTEM')),
    actor_id         BIGINT,
    room_id          BIGINT      REFERENCES room (id) ON DELETE CASCADE,
    action           VARCHAR(32) NOT NULL,
    target_member_id BIGINT,
    detail           JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_room ON audit_log (room_id, created_at);
