-- ⑥ 관리자 (docs/todo/room-api-db.md 1절 V26, room-plan.md 10절)
-- 계정 행은 여기에 넣지 않는다: 운영 DB에 직접 INSERT (tools/admin-account, 사용자가 직접 실행). 비밀번호·해시·OTP 비밀값은 저장소에 두지 않는다
CREATE TABLE admin_user (
    id              BIGSERIAL    PRIMARY KEY,
    email           VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash   TEXT         NOT NULL,
    -- TOTP 비밀값: AES-GCM 암호문(base64, iv 12바이트 + 암호문·태그). 키는 환경 변수 ADMIN_TOTP_KEY
    totp_secret     TEXT         NOT NULL,
    -- 마지막으로 받은 TOTP 시간 칸 (같은 코드 재사용 막기)
    totp_last_step  BIGINT       NOT NULL DEFAULT 0,
    -- 비밀번호·OTP 실패를 합쳐 센다. 5번이면 15분 잠금
    failed_count    SMALLINT     NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 로그인 세션: 비밀번호를 통과하면 PENDING_OTP(5분), OTP까지 통과하면 ACTIVE(1시간). 토큰 원문은 저장하지 않는다
CREATE TABLE admin_session (
    id          BIGSERIAL   PRIMARY KEY,
    token_hash  BYTEA       NOT NULL UNIQUE,
    admin_id    BIGINT      NOT NULL REFERENCES admin_user (id) ON DELETE CASCADE,
    stage       VARCHAR(12) NOT NULL CHECK (stage IN ('PENDING_OTP', 'ACTIVE')),
    ip          VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX idx_admin_session_admin ON admin_session (admin_id);

-- 되살릴 때 원래 상태(ACTIVE·CLOSED)로
ALTER TABLE room ADD COLUMN status_before_hide VARCHAR(8);

-- 감사 기록은 방이 지워져도 1년 보관 (11-13 결정). 방 공개 id·이름은 detail에 남고,
-- 방을 완전 삭제할 때 그 방 기록의 IP·닉네임은 지운다(RoomRepository.purgeHidden). 1년 지난 기록은 정리 작업이 지운다
ALTER TABLE audit_log DROP CONSTRAINT audit_log_room_id_fkey;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_room_id_fkey FOREIGN KEY (room_id) REFERENCES room (id) ON DELETE SET NULL;
CREATE INDEX idx_audit_log_created ON audit_log (created_at);
CREATE INDEX idx_audit_log_actor ON audit_log (actor_type, actor_id, created_at);
-- 이미 쌓인 기록에도 방 공개 id·이름을 채운다
UPDATE audit_log a SET detail = coalesce(a.detail, '{}'::jsonb) || jsonb_build_object('roomPublicId', r.public_id, 'roomName', r.name)
FROM room r WHERE r.id = a.room_id;
