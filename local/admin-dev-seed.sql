-- ⑥ 로컬 개발 전용 관리자 계정 (가짜 값, 커밋 가능). 운영 DB에는 절대 넣지 않는다.
-- 이메일 admin@local.test / 비밀번호 local-admin-only / OTP 비밀값 JBSWY3DPEHPK3PXP (인증 앱에 직접 입력)
-- totp_secret은 application-local.yml의 로컬 가짜 키로 암호화한 값이라 로컬 API에서만 풀린다.
-- 넣기: docker exec -i gyeol-local-postgres-1 psql -U gyeol -d gyeol < local/admin-dev-seed.sql
INSERT INTO admin_user (email, password_hash, totp_secret)
VALUES ('admin@local.test', '$2b$10$KDWkNac.mzL0fN344vR5x.SYZoHxobeCJFn3jNvHKz0kD6DkHCFZO', 'PbZSQdJAAeQ4i66urAmHojluYnezolz8PmJm2OXHsk/CKQP7x0NOO0fGJCg=')
ON CONFLICT (email) DO NOTHING;
