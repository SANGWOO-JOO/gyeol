# 배포 전 결정 정리 (2026-11-14, 초안)

음악 방 기능이 로컬에서 끝난 뒤(`parallel.md`), 실제 AWS에 올리기 전에 정할 것과 비용을 한곳에 모은다.
근거: `single-server.md`(11-10 비용 조회), `design-v3.md`(EKS 회차), 저장소 코드·설정(11-14 확인). 가격 중 "확인 필요"는 공식 가격표로 다시 볼 것.

## 0. 요약: 결정이 필요한 것

| # | 결정 | 추천 | 비용 영향 |
|---|---|---|---|
| D1 | 배포 순서 | 아래 1절 순서(로컬 k3d 확인 → base apply → CI → 단일 서버 → 실제 확인 → EKS 회차 → destroy) | 단계마다 |
| D2 | 도메인 | **바꿈(11-16): `gyeol.blog`, Porkbun에서 첫해 2.06달러로 구매(갱신 21.11달러, 자동 갱신 끔), DNS는 Cloudflare 무료로 네임서버 위임.** 이전 결정(11-14 `ourgyeol.com`, Cloudflare Registrar 약 10.5달러)은 1주일쯤 운영 뒤 내릴 계획이라 비용 때문에 바꿈. `gyeol.click`은 Porkbun 조회 오류로 못 삼. 방장 이메일 인증 발신(`noreply@gyeol.blog`)과 웹 주소에 함께 씀. Route 53 호스팅 영역을 쓰지 않으므로 `ses.tf`는 `route53_zone_id` 없이, 출력된 DKIM·SPF·MX·DMARC 값을 Cloudflare DNS에 직접 넣음. 11-14 whois 기준 미등록, 구매 직전 재확인. 구매·결제는 사용자가 직접 | 연 약 10.5달러(.com 원가, 갱신가 구매 때 확인), DNS 0 |
| D3 | 선물 캐릭터 이미지 하루 한도 | **정함(11-14): 10장, 선착순을 화면에 표시**(`room-plan.md` ⑯ 변경). 앱 안의 제한이라, 운영에서는 AWS Budgets 자동 조치(넘으면 Bedrock 권한 회수)를 함께 걸지 검토 | 최대 하루 0.4달러, 월 최대 약 12달러 |
| D4 | 운영 음악 지도 데이터 | **로컬에서 만든 지도(1,458명)를 덤프해 운영 DB로 옮김.** 다시 만들면 Bedrock 약 2.5달러 + MusicBrainz 약 2시간 | 0 (옮기기) |
| D5 | 관리자 보안 값 보관 | `ADMIN_TOTP_KEY`·API 키는 SSM Parameter Store(SecureString)에, 관리자 경로는 Traefik IP 허용 | 거의 0 |
| D6 | 상시 운영 형태 | 11-10 결정 유지: 단일 서버(t4g.medium 스팟 + k3s). EKS는 측정 회차에만 올리고 내림 | 아래 3절 |

## 1. 배포 순서 (D1)

11-10에 정한 순서에 음악 방 작업을 반영했다.

| 단계 | 할 일 | 비용 | 사전 조건 |
|---|---|---|---|
| 0 | **로컬 k3d 확인**: V21~V26, 새 큐 3개(room-song·gift-image·code-match), 웹 새 화면이 k3d(Helm)에서 도는지 | 0 | **Helm·Terraform에 새 큐 3개가 빠져 있음(2절)** |
| 1 | `infra/terraform/base` apply: SQS·S3·IAM·예산 알림(SES는 D2 뒤) | 거의 0 (안 쓰면) | 0단계, `terraform plan` 재확인 |
| 2 | CI + Release: 테스트 → 이미지(GHCR) → 릴리즈 노트 | GitHub 무료 범위 | — |
| 3 | 단일 서버 Terraform + OIDC 배포 역할(태그 push → SSM으로 helm upgrade) | 월 약 17.5달러부터 | 1·2단계, D2(HTTPS 주소) |
| 4 | 실제 AWS 확인: 업로드·Bedrock·SES 발송·백업·관리자 로그인 | Bedrock 쓴 만큼 | D2 DNS·DKIM, SES 샌드박스 해제 |
| 5 | EKS 회차(측정) → destroy | 회차당 수 달러(추정) | 3·4단계 |
| 6 | README·이력서 수치 | 0 | — |

## 2. 지금 저장소에서 빠진 것 (배포 전에 고칠 것)

- **새 큐 3개가 운영 설정에 없음(11-14 확인, 사실):** `infra/terraform/base/sqs.tf`의 큐는 parse·identify·cover·enrich·recommend·guess·artist-image 7개뿐이고, Helm `values.yaml`에도 같은 7개만 있다. 음악 방이 쓰는 `room-song`(표시 제한 900초)·`gift-image`(300초)·`code-match`(120초)와 실패 큐를 둘 다에 더해야 한다. I-1에서 큐 자동 생성을 끄면, 빠진 큐 때문에 worker가 시작부터 실패하므로 꼭 함께 고친다.
- IAM 앱 정책: 선물 이미지 모델(`stability.stable-image-core-v1:1`, us-west-2) 호출 권한이 `bedrock_models`·리전에 없음. 이미지 모델을 쓸 때 더한다.
- SES: `ses.tf`는 `mail_domain`이 비면 자원을 만들지 않는다. D2 뒤에 값을 넣는다.
- 관리자 IP 허용, `ADMIN_TOTP_KEY`: Helm 값·Traefik 미들웨어로 아직 없음.

## 3. 비용 (월, 서울 리전)

| 항목 | 월 | 근거 |
|---|---|---|
| 단일 서버(t4g.medium 스팟 2d + EBS 30GB + IPv4 + SQS + S3, 부가세 포함) | 약 17.5달러 | `single-server.md` 11-10 조회 |
| 도메인 `gyeol.blog` (Porkbun 등록, Cloudflare DNS 무료) | 첫해 2.06달러 한 번 | 11-16 구매. 갱신가 21.11달러라 자동 갱신 끔 |
| SES 발송 | 0에 가까움 (1,000통당 0.10달러) | 방 만들기·재설정 메일만 |
| Bedrock Haiku (청취 분석·코드 맞추기 이유·새 가수 설명) | 쓴 만큼 (처음 사용자 업로드 1회 약 0.25달러, 코드 맞추기 이유 1건 약 0.002달러) | 로컬 측정·추정 |
| 선물 캐릭터 이미지(Stable Image Core, 장당 약 0.04달러) | 한도 10장이면 최대 월 약 12달러, 50장이면 약 60달러 | 설정 `daily-image-limit`, 단가 **확인 필요** |
| **합계 (사용량 적을 때)** | **약 18.5달러 + 쓴 만큼** | |

- 시연하지 않는 기간에는 서버를 정지하면 EBS·IP 약 6달러만 남는다(`single-server.md`).
- EKS를 상시로 두지 않는 이유: 컨트롤 플레인만 월 약 73달러, 로드밸런서·NAT도 필요.

## 4. SES 메모 (D2를 정한 뒤)

- 순서: 도메인 등록 → `mail_domain`·`route53_zone_id`를 tfvars에 → apply(도메인 인증·DKIM·MAIL FROM·DMARC 레코드) → DKIM 확인 → 샌드박스 해제 신청(콘솔).
- 샌드박스: 인증한 받는 주소에만 보낼 수 있고 하루 200통·초당 1통이라는 자료가 있음(확인 필요). 해제 신청 답은 보통 하루 안이라는 AWS 안내가 있으나 자료마다 다름.
- 무료 사용량: 예전 "EC2에서 월 62,000통 무료"는 지금 기준과 다르다는 자료가 많음. 비용이 작아 결정에는 영향 없음.

## 5. 기록

| 날짜 | 내용 |
|---|---|
| 11-14 | 초안. 관리 세션이 기존 문서·코드·웹 검색으로 정리 |
| 11-14 | D2 `gyeol.blog`(Cloudflare) 결정, D3 10장 선착순 결정 |

## 0단계 결과 (2026-11-16, 로컬 k3d)

### 배포 0단계: 로컬 k3d 최종 확인 (11-16, 갈래 1)

### 결론
- 오늘까지 바뀐 내용이 배포 이미지와 Helm(k3d)에서 모두 동작했다.
  - 빈 DB에서 V1~V35 35건이 0.14초에 적용됐다.
  - 파드 6개(api·worker·fetcher·web·embed·postgres)의 재시작은 0번이다.
  - 아래 흐름을 실제로 확인했다: 첫 화면 → 방 만들기(가짜 메일 코드) → 참가 → 검색(미리 준비) → 담기 → 지도, 약관 2개, 업로드.
- 빠진 설정 4가지를 찾아 고쳤다(커밋 안 함).
  1. Helm api에 `WEB_ORIGIN`이 없었다. 관리자 API의 Origin 검사가 운영 주소에서 403이 된다.
  2. Helm에 `ROOM_SECURE_COOKIE`·`ADMIN_SECURE_COOKIE`가 없었다. 운영 쿠키에 Secure가 빠진다.
  3. Helm에 `ADMIN_TOTP_KEY`가 없었다. 관리자 로그인이 503이 된다.
  4. 웹 nginx에 gzip이 없었다. CSS 512KB·JS 613KB를 압축 없이 보냈다.
  - 그 밖에: Terraform `web_origins` 기본값이 비어 있어 운영 업로드 CORS가 만들어지지 않았다. `index.html` 캐시 헤더도 없었다.
- `room.notFoundRetry`(앱 기본 30일), V35(큐는 room-song을 함께 씀, 새 큐·권한 없음), RateGate는 새 환경 변수가 필요 없다.

### 1. k3d 구성 변경 (설계 판단이 달라진 점)
- 전: k3d가 개발 환경과 같은 compose DB(15432)와 LocalStack(4566)을 썼다.
  - 이제는 dev-up이 늘 켜져 있어, 같은 큐를 개발 worker와 k3d worker가 나눠 읽는다. 그러면 메시지가 다른 DB에 저장된다.
- 후: 개발 환경과 데이터를 나눈다.
  - DB는 클러스터 안 PostgreSQL(`postgres.enabled=true`, 2Gi)이다. 단일 서버와 같은 구성이다.
  - LocalStack은 k3d 전용 컨테이너 `gyeol-k3d-localstack`(14566)이다. 같은 init-aws.sh를 쓴다.
  - `k3d-up.sh`가 켜고, `k3d-down.sh`가 지운다. 입구 포트는 그대로 18000이다.
- 개발 DB에 시험 방이 생기지 않은 것을 확인했다(FXR8mgShrL 0행).

### 2. 이미지 크기 (docker images, 압축 전)
| 이미지 | 크기 |
|---|---|
| api | 388MB |
| worker | 384MB |
| external-fetcher | 339MB |
| web | 136MB (정적 파일 22.7MB, 그중 woff 12MB·woff2 9.4MB) |
| embed | 1.25GB |

### I-64 판단
- woff 대체본 12MB는 이미지에만 있다. 사용자는 받지 않는다.
  - 브라우저 측정: 글꼴 요청 woff2 19개, woff 0개, 외부 출처 요청 0개.
  - 지금 브라우저는 모두 woff2를 지원하므로 woff는 고르지 않는다.
- 사용자가 받는 비용은 CSS다.
  - 512KB(@font-face 선언) → gzip을 켜서 148KB.
  - 이 작업 전에는 압축 없이 512KB를 그대로 보냈다(위 4번).
- 추천: 배포를 막는 문제가 아니므로 I-64는 열어 둔다.
  - 줄일 때는 woff 제외(이미지 −12MB)보다 Noto Sans KR 500 굵기 빼기(CSS 약 1/3 감소 추정)가 사용자에게 효과가 크다.

### 3. 흐름 확인 (k3d, http://localhost:18000)
| 항목 | 결과 |
|---|---|
| 첫 화면 | 200. 글꼴 4종(Noto Sans KR 400·700, Gowun Batang 700, Quicksand 700)을 같은 출처에서 받음 |
| 방 만들기 | 가짜 메일 코드는 `kubectl logs`에서 읽음 → 201, 방 FXR8mgShrL |
| 참가 | 201. 쿠키 Max-Age 15552000(180일), HttpOnly·SameSite=Lax, Path=/api/rooms/{rid}. k3d라 Secure 없음 |
| 쿠키 재발급 | GET /me 200 + Set-Cookie 재발급 + Cache-Control: no-store |
| 검색 | 아이유 → source db+toplist, 가수 카드, 10곡. 가수 미리 준비가 worker에서 돎(묶음 7~18초, Last.fm·MusicBrainz 무료 호출). 다른 검색어 0.37초 |
| 담기 | 화면에서 "좋은 날" 담음. API로 "밤편지"는 미리 준비로 이미 식별돼 201·0.015초(I-74 경로) |
| 표지 | /api/rooms/{rid}/covers/5 200 image/jpeg. k3d LocalStack S3에 객체 8개 |
| 지도 | 200. planets 1, members 2, **placeholder true, galaxies 0** |
| 약관 | /terms·/privacy 200 |
| 업로드 | 시험 Takeout 300건 → 미리보기(음악 76) → "동의하고 올리기" → 서명 URL로 S3(14566) PUT → parse 76건 55ms → 결과 화면. video-check는 페이지에서 가짜 응답으로 막음(YouTube 호출 0) |
| 정리 작업 | `CLEANUP_CRON`을 1분으로 잠깐 바꿔 확인. 오래된 시험 행을 넣어 세 단계가 지우는지 봄. 확인 뒤 되돌림 |
| 관리자 | Origin localhost:18000 → 401(로그인 실패, 키가 있어 503 아님). Origin localhost:15173 → 403. WEB_ORIGIN이 반영됨 |

- 정리 작업에서 지운 것
  - 판정 경로 별칭(29일 지남) 삭제
  - 쓰지 않는 방(400일) → HIDDEN, 7일 뒤 완전 삭제 예약
  - 감사 기록 IP(91일) 제거, 다른 필드는 유지

### 메모리 (kubectl top, 처리 직후)
- api 315Mi, worker 290Mi, fetcher 214Mi, embed 819Mi, postgres 109Mi, web 8Mi → 합계 약 1.75GiB
- k3d LocalStack 175MiB. 11-10 측정과 비슷하다.

### 4. Helm·Terraform 고친 것
| 파일 | 내용 |
|---|---|
| `deploy/helm/gyeol/templates/backend.yaml` | api에 `WEB_ORIGIN`, `ROOM_SECURE_COOKIE`·`ADMIN_SECURE_COOKIE`(api.secureCookie), `ADMIN_TOTP_KEY`(gyeol-secrets, optional) |
| `values.yaml` | `api.webOrigin: ""`, `api.secureCookie: false`, 비밀 값 설명에 ADMIN_TOTP_KEY |
| `values-local.yaml` | 클러스터 안 DB, LocalStack 14566, webOrigin localhost:18000 |
| `values-single.yaml` | `webOrigin: https://gyeol.blog`, `secureCookie: true` |
| `deploy/docker/web-nginx.conf` | gzip(css·js·json·svg), `index.html`은 no-cache |
| `scripts/k3d-up.sh`·`k3d-down.sh` | k3d 전용 LocalStack, 비밀 값에 로컬 가짜 ADMIN_TOTP_KEY(application-local.yml 값, 운영 키 아님) |
| `infra/terraform/base/variables.tf`·`tfvars.example` | `web_origins` 기본값 `["https://gyeol.blog"]` |

- `terraform plan`(apply 안 함): 36 to add, 0 change. 큐 10쌍, 버킷 3, 업로드 CORS(이번에 들어감), 알림, IAM, 예산.
- `helm lint`(values-single): 통과

### 5. 남은 것 (배포 전, 이번에 고치지 않음)
- **D4 지도 데이터:** 빈 DB에서는 지도가 placeholder(은하 0)다. 운영에 로컬 지도를 옮기는 것이 필수임을 확인했다.
  - DB 덤프만으로는 부족하다. 가수 사진·표지 S3 객체(covers 버킷)도 함께 옮겨야 한다.
  - 대상 테이블 목록과 YouTube 파생(채널 이미지·판정 별칭) 제외 범위는 따로 정해야 한다.
- **처리방침·약관의 `{시행일}`:** 자리표시가 그대로 화면에 나온다. 테스트(`markdown.test.ts`)가 일부러 확인하는 자리표시라, 배포 때 날짜를 넣는 절차가 필요하다.
- **values-single 메일:** 아직 `mode: log`다. D2 SES 인증 뒤 `ses`와 `MAIL_FROM`을 넣는다.
- **단일 서버 HTTPS:** secureCookie true는 HTTPS가 있어야 쿠키가 붙는다. Traefik 인증서(ACME) 또는 Cloudflare 프록시 중 단계 3에서 정한다.
- **선물 이미지 모델 IAM:** 기존 2절 항목 그대로다.
- **새 클러스터 첫 기동:** k3s 시스템 이미지(flannel·coredns·traefik)를 받느라 약 8분이 걸려 `helm --wait 5m`이 시간 초과됐다. 파드는 그 뒤 재시작 없이 떴다. 단일 서버 첫 설치도 시간 여유(10분)가 필요하다.
- 맥북 정리: k3d 클러스터와 k3d LocalStack은 지웠다. 이미지 gyeol/*:20261116153743(약 2.8GB)은 다음 k3d-up 때 지워진다.

### 6. 트러블슈팅
1. helm `--wait` 시간 초과(위). 원인은 새 클러스터의 시스템 이미지 받기다. 기다리니 해결됐다.
2. 시험 데이터 넣기 첫 시도에서 `title_alias.method` NOT NULL에 걸렸다(내 SQL 실수). 고쳐서 다시 넣었다.
3. 개발 api·worker·fetcher를 빌드·k3d 동안 멈췄다가 다시 켰다. 다시 켤 때 worker는 LLM_MODE=fake, MAP_ADMIN_ENDPOINT=true였다. 지금 dev-status는 7개 모두 ✓이고, 리뷰 방·사용자 방 2개는 ACTIVE 그대로다.
