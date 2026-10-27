#!/usr/bin/env bash
# 로컬 개발용: 세션 결과 링크를 새로 발급한다 (DB에는 토큰 해시만 있어 옛 링크는 되살릴 수 없다). 옛 링크는 더 이상 열리지 않는다
# 사용: scripts/dev-reissue-link.sh <세션 id>
set -eu
SID=$1
TOKEN=$(python3 -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('='))")
HASH=$(printf '%s' "$TOKEN" | shasum -a 256 | cut -d' ' -f1)
n=$(docker exec "$(docker ps -qf name=postgres)" psql -U gyeol -d gyeol -Atc "UPDATE session SET token_hash = decode('$HASH', 'hex') WHERE id = $SID AND expires_at > now() RETURNING id" | head -1)
[ -n "$n" ] || { echo "세션 $SID 없음 또는 만료"; exit 1; }
echo "http://localhost:15173/r/$TOKEN"
