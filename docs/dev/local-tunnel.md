# 로컬 서비스를 잠깐 외부에 열기 (Cloudflare Quick Tunnel)

2026-11-15. AWS에 배포하지 않고, 맥북에서 돌고 있는 결 개발 환경을 지인에게 몇 시간 열어 준 기록이다. 다음에 다시 열 때 쓰는 절차와, 공부할 개념을 함께 적는다.

## 1. 한눈에 보기

```
지인 브라우저 ──HTTPS──▶ Cloudflare 엣지 (xxxx.trycloudflare.com)
                              ▲
                              │ cloudflared가 맥북에서 먼저 연결해 둔 터널 (밖으로 나가는 연결)
                              │
맥북: cloudflared ──▶ Vite 개발 서버 :15173 ──(/api 중계)──▶ api :18080 ──▶ worker·fetcher·DB·LocalStack
```

- **공유기 포트를 열지 않는다.** `cloudflared`가 맥북에서 Cloudflare로 **나가는** 연결을 먼저 만들고, 외부 요청은 그 연결을 거꾸로 타고 들어온다. 이것이 리버스 터널(reverse tunnel) 방식이다.
- **HTTPS는 Cloudflare가 처리한다.** 인증서를 직접 만들 필요가 없다. 맥북 안쪽 구간은 `http://localhost:15173`이다.
- **Quick Tunnel은 가입 없이 쓰는 임시 터널이다.** 켤 때마다 주소가 바뀌고, 가동 시간 보장(SLA)이 없다. 고정 주소가 필요하면 Cloudflare 계정에서 Named Tunnel을 만든다.

## 2. 여는 절차

1. **설치 (한 번만)**
   ```bash
   brew install cloudflared
   ```
2. **로컬 서비스 켜기:** `scripts/dev-up.sh`. 웹은 15173, api는 18080이다.
3. **터널 켜기:** 아래 명령을 백그라운드로 실행하고 로그를 남긴다.
   ```bash
   cd ~/Desktop/github/gyeol && nohup cloudflared tunnel --url http://localhost:15173 --no-autoupdate > .run/tunnel.log 2>&1 & echo $! > .run/tunnel.pid
   ```
4. **주소 확인:** 아래 명령으로 `https://<단어들>.trycloudflare.com` 주소를 찾는다.
   ```bash
   grep -oE "https://[a-z0-9-]+\.trycloudflare\.com" .run/tunnel.log | head -1
   ```
5. **확인:** 첫 화면·방 화면·약관은 200, `/upload`·`/admin`은 403이면 정상이다.
6. **방 만들기:** 맥북에서 `<터널주소>/rooms/new`로 만든다.
   - 로컬은 메일을 보내지 않는다. 인증 코드는 로그에 찍힌다(`LogMailSender`).
     ```bash
     grep -a "가짜 메일" .run/api.log | tail -1
     ```
   - 새 코드를 받으면 이전 코드는 쓸 수 없다.
7. **초대:** 방에서 "친구 초대하기"를 누르면 초대 문구에 현재 주소(`location.origin`)가 들어간다. 터널로 연 화면에서 누르면 터널 주소가 들어간다. 참가자는 이메일 없이 닉네임과 비밀번호 4자리로 들어온다.

## 3. 코드에서 바꾼 것 (`web/vite.config.ts`, 개발 서버 전용)

| 설정 | 이유 |
|---|---|
| `server.allowedHosts: ['.trycloudflare.com']` | Vite는 모르는 `Host` 헤더로 온 요청을 막는다(DNS rebinding 방어). 터널 주소를 허용 목록에 넣는다 |
| `tunnelGuard` 플러그인 (미들웨어) | 터널 주소로 들어온 요청 중 `/upload`, `/admin`, `/api/admin`, 업로드 API(`/api/sessions` 생성, `upload-url`, `seeds`)를 403으로 막는다. 외부 사람의 시청 기록이 맥북 DB에 쌓이지 않게 하고, 관리자 화면을 밖에 보이지 않게 하기 위해서다. 같은 와이파이·localhost 접속은 막지 않는다 |
| `/api` 중계에서 `Origin`을 `http://localhost:15173`으로 바꿈 (I-71) | api의 CORS 허용 출처는 `web-origin` 하나(localhost:15173)다. 다른 주소로 연 화면의 POST는 `Origin`이 달라 403이 된다. 브라우저 입장에서는 화면과 API가 같은 출처이므로, 중계할 때만 바꿔 준다 |

- 터널 판별은 `x-forwarded-host`와 `host` 헤더 중 하나가 `*.trycloudflare.com`인지로 한다.
- 운영(CloudFront 단일 도메인)에는 이 설정이 필요 없다. 개발 서버에만 있는 코드다.

## 4. 실제 AI로 바꾸기 (선택, 비용 발생)

- 기본은 `LLM_MODE=fake`다(`backend/worker/src/main/resources/application.yml`). `.env`에 값이 없으면 fake로 동작한다.
- 터널을 여는 동안만 바꾸려면 `.env`는 그대로 두고 worker만 환경 변수를 줘서 다시 켠다.
  ```bash
  kill $(cat .run/worker.pid)
  (set -a; . ./.env; set +a; LLM_MODE=bedrock SPRING_PROFILES_ACTIVE=local nohup "$(/usr/libexec/java_home -v 21)/bin/java" -jar backend/worker/build/libs/worker-0.1.0.jar > .run/worker.log 2>&1 & echo $! > .run/worker.pid)
  ```
- 맥북의 AWS 자격 증명으로 Bedrock을 부른다. 곡 설명, 코드 맞추기 설명, 선물 그림(장당 약 0.04달러, 하루 10장)에 비용이 든다.
- 배포용 "월 15달러 넘으면 Bedrock 차단"은 AWS에 아직 만들지 않았다. 로컬에서는 앱의 하루 한도만 걸려 있다.
- 되돌리기: worker를 다시 켜거나 `dev-up.sh`로 다시 시작하면 fake로 돌아간다.

## 5. 닫는 절차

1. 터널 끄기
   ```bash
   kill $(cat .run/tunnel.pid)
   ```
2. bedrock으로 바꿨다면 worker를 fake로 다시 켠다.
3. 시험 방을 정리한다. 참가자 데이터는 DB에 남는다.
4. 비용을 확인한다. Bedrock을 썼다면 `llm_call` 테이블 건수나 AWS Cost Explorer로 본다.

## 6. 주의할 점

- **맥북 상태:** 맥북이 잠자기에 들어가거나 네트워크가 바뀌면 끊긴다. 덮개를 열어 두거나 `caffeinate`를 쓴다.
- **속도:** 개발 서버(번들 안 된 모듈)라 첫 로딩이 느리다. 사람이 많으면 `vite build` 뒤 미리보기 서버로 띄우는 편이 낫다.
- **외부 API:** 외부 사람의 검색·담기도 맥북에서 MusicBrainz(초당 1건)와 Last.fm을 부른다. 사람이 몰리면 느려진다(I-73).
- **약관:** 운영자 이름·연락처가 아직 자리표시자다. 지인 테스트까지만 쓴다.
- **주소:** Quick Tunnel 주소는 누구나 알면 들어올 수 있다. 공개 게시판에 올리지 않는다.

## 7. 트러블슈팅

| 증상 | 원인 | 처리 |
|---|---|---|
| 터널 주소로 열면 "Blocked request. This host is not allowed" | Vite `allowedHosts`에 없음 | `.trycloudflare.com` 추가 |
| 화면은 열리는데 참가·담기만 "요청을 처리하지 못했어요" | 중계된 POST의 `Origin`이 달라 api CORS가 본문 없는 403으로 거절 | 중계에서 Origin을 localhost:15173으로 바꿈 (I-71) |
| 인증 메일이 안 옴 | 로컬은 `LogMailSender` | api 로그에서 코드 확인 |
| 터널을 다시 켰더니 초대 링크가 안 열림 | Quick Tunnel 주소가 바뀜 | 새 주소로 다시 초대 |
| worker를 다시 켰는데 바로 죽음 ("Port 18082 was already in use") | 이전 worker가 graceful shutdown 중(약 2초)인데 새 프로세스를 먼저 띄움 | `lsof -iTCP:18082`로 포트가 비었는지 확인한 뒤 켠다 (11-16 실제 겪음) |
| `kill $(cat .run/tunnel.pid)`로 안 꺼짐 | nohup으로 띄울 때 pid 파일이 다른 프로세스를 가리킴 | `pkill -f "cloudflared tunnel"` |

## 8. 11-15 사용 기록

- 터널 연 시간: 11-15 18:59 ~ 11-16 15:33
- 실제 AI 모드: 11-15 19:05 ~ 11-16 15:33
- 만든 방: 시공삶(4명, 2곡), 여찾사(3명, 4곡)
- Bedrock 호출: Haiku 4.5 13건(곡 설명 3, 가수 설명 2, 제목 다시 쓰기 8), 입력 약 19.6K·출력 약 1.5K 토큰 → 약 0.03달러(추정, 입력 1M당 1달러·출력 1M당 5달러 기준). 선물 그림 생성 0장

## 9. 공부할 개념

- **리버스 터널 / 아웃바운드 전용 연결:** 포트를 열지 않고 내부 서비스를 노출하는 방식. 같은 방식을 쓰는 도구로 ngrok, Tailscale Funnel, Cloudflare Named Tunnel(Zero Trust)이 있다.
- **Host 헤더 검사와 DNS rebinding:** 개발 서버가 왜 모르는 Host를 막는가.
- **CORS와 Origin 헤더:** 같은 출처 요청에서도 브라우저가 POST에 Origin을 붙이는 이유, 그리고 서버가 허용 출처를 검사하는 방식.
- **`X-Forwarded-Host`·`X-Forwarded-For`:** 프록시·터널을 거친 요청에서 원래 주소를 아는 법.
- **TLS 종료(termination):** HTTPS를 Cloudflare 엣지에서 끝내고, 안쪽은 http로 보내는 구조.
- **Quick Tunnel과 Named Tunnel의 차이:** 고정 도메인, 접근 정책(Access), 서비스 토큰.
