"""결 관리자 계정 만들기 (docs/todo/room-api-db.md 12절).

사용자가 자기 터미널에서 직접 실행한다. Claude 세션에서 실행하지 않는다(OTP 비밀값이 대화 기록에 남는다).
출력(INSERT 문, 인증 앱 등록 주소)은 화면에만 나온다. 파일로 저장하거나 저장소에 넣지 않는다.

  ADMIN_TOTP_KEY=<운영 키> uv run --directory tools/admin-account python make_admin.py
  uv run --directory tools/admin-account python make_admin.py --self-test   # 가짜 값으로 형식만 확인

- 비밀번호: BCrypt(cost 12), API의 BCryptPasswordEncoder와 호환
- OTP 비밀값: 20바이트 → Base32, AES-256-GCM(iv 12바이트 + 암호문·태그, base64)으로 암호화. API TotpCipher와 같은 형식
"""
import argparse
import base64
import getpass
import hashlib
import hmac
import os
import secrets
import struct
import sys
import urllib.parse

import bcrypt
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def encrypt(key_b64: str, plain: str) -> str:
    key = base64.b64decode(key_b64)
    if len(key) != 32:
        raise SystemExit("ADMIN_TOTP_KEY는 base64로 된 32바이트여야 합니다")
    iv = secrets.token_bytes(12)
    return base64.b64encode(iv + AESGCM(key).encrypt(iv, plain.encode(), None)).decode()


def decrypt(key_b64: str, stored: str) -> str:
    raw = base64.b64decode(stored)
    return AESGCM(base64.b64decode(key_b64)).decrypt(raw[:12], raw[12:], None).decode()


def totp(secret_b32: str, step: int) -> str:
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    o = h[-1] & 0x0F
    return str((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1_000_000).zfill(6)


def sql_quote(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def self_test() -> None:
    # RFC 6238 부록 B (SHA1, 비밀값 "12345678901234567890"): 59초 → 94287082의 뒤 6자리
    secret = base64.b32encode(b"12345678901234567890").decode()
    assert totp(secret, 59 // 30) == "287082", "TOTP 계산이 RFC 6238과 다릅니다"
    fake_key = base64.b64encode(b"x" * 32).decode()
    assert decrypt(fake_key, encrypt(fake_key, secret)) == secret, "암호화 왕복 실패"
    h = bcrypt.hashpw(b"fake-password", bcrypt.gensalt(12)).decode()
    assert h.startswith("$2b$12$") and bcrypt.checkpw(b"fake-password", h.encode())
    print("자체 확인 통과: TOTP(RFC 6238), AES-GCM 왕복, BCrypt")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--self-test", action="store_true", help="가짜 값으로 형식만 확인")
    p.add_argument("--issuer", default="gyeol")
    args = p.parse_args()
    if args.self_test:
        self_test()
        return
    key = os.environ.get("ADMIN_TOTP_KEY") or getpass.getpass("ADMIN_TOTP_KEY (base64 32바이트, 화면에 안 보임): ")
    email = input("관리자 이메일: ").strip().lower()
    if "@" not in email:
        raise SystemExit("이메일 형식을 확인해 주세요")
    pw = getpass.getpass("비밀번호 (12자 이상): ")
    if len(pw) < 12 or pw != getpass.getpass("비밀번호 다시: "):
        raise SystemExit("비밀번호가 12자 미만이거나 두 번 입력이 다릅니다")
    pw_hash = bcrypt.hashpw(pw.encode(), bcrypt.gensalt(12)).decode()
    # Spring BCryptPasswordEncoder는 $2a·$2b 둘 다 받는다
    secret = base64.b32encode(secrets.token_bytes(20)).decode().rstrip("=")
    enc = encrypt(key, secret)
    uri = "otpauth://totp/" + urllib.parse.quote(f"{args.issuer}:{email}") + "?" + urllib.parse.urlencode(
        {"secret": secret, "issuer": args.issuer, "algorithm": "SHA1", "digits": 6, "period": 30})
    print("\n1) 인증 앱에 등록 (주소를 앱에 붙여 넣거나 비밀값을 직접 입력). 등록 뒤 이 화면을 지우세요")
    print("   ", uri)
    print("\n2) 운영 DB에 실행할 INSERT 문 (파일로 저장하지 마세요)")
    print(f"   INSERT INTO admin_user (email, password_hash, totp_secret) VALUES ({sql_quote(email)}, {sql_quote(pw_hash)}, {sql_quote(enc)});")
    print("\n3) 확인: 인증 앱의 지금 코드 =", totp(secret, int(__import__('time').time()) // 30), "(앱과 같으면 등록 완료)")


if __name__ == "__main__":
    sys.exit(main())
