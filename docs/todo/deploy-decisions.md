# 배포 전 결정 정리 (2026-11-14, 초안)

음악 방 기능이 로컬에서 끝난 뒤(`parallel.md`), 실제 AWS에 올리기 전에 정할 것과 비용을 한곳에 모은다.
근거: `single-server.md`(11-10 비용 조회), `design-v3.md`(EKS 회차), 저장소 코드·설정(11-14 확인). 가격 중 "확인 필요"는 공식 가격표로 다시 볼 것.

## 0. 요약: 결정이 필요한 것

| # | 결정 | 추천 | 비용 영향 |
|---|---|---|---|
| D1 | 배포 순서 | 아래 1절 순서(로컬 k3d 확인 → base apply → CI → 단일 서버 → 실제 확인 → EKS 회차 → destroy) | 단계마다 |
| D2 | 도메인 | **바꿈(11-16): `gyeol.blog`, Porkbun에서 첫해 2.06달러로 구매(갱신 21.11달러, 자동 갱신 끔), DNS는 Porkbun DNS 그대로(11-16 결정, Cloudflare 안 씀).** 이전 결정(11-14 `ourgyeol.com`, Cloudflare Registrar 약 10.5달러)은 1주일쯤 운영 뒤 내릴 계획이라 비용 때문에 바꿈. `gyeol.click`은 Porkbun 조회 오류로 못 삼. 방장 이메일 인증 발신(`noreply@gyeol.blog`)과 웹 주소에 함께 씀. Route 53 호스팅 영역을 쓰지 않으므로 `ses.tf`는 `route53_zone_id` 없이, 출력된 DKIM·SPF·MX·DMARC 값을 Cloudflare DNS에 직접 넣음. 11-14 whois 기준 미등록, 구매 직전 재확인. 구매·결제는 사용자가 직접 | 연 약 10.5달러(.com 원가, 갱신가 구매 때 확인), DNS 0 |
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
| 도메인 `gyeol.blog` (Porkbun 등록·DNS, 무료) | 첫해 2.06달러 한 번 | 11-16 구매. 갱신가 21.11달러라 자동 갱신 끔 |
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
- **단일 서버 HTTPS:** secureCookie true는 HTTPS가 있어야 쿠키가 붙는다. Traefik 인증서(ACME, Porkbun DNS라 이 방식) 중 단계 3에서 정한다.
- **선물 이미지 모델 IAM:** 기존 2절 항목 그대로다.
- **새 클러스터 첫 기동:** k3s 시스템 이미지(flannel·coredns·traefik)를 받느라 약 8분이 걸려 `helm --wait 5m`이 시간 초과됐다. 파드는 그 뒤 재시작 없이 떴다. 단일 서버 첫 설치도 시간 여유(10분)가 필요하다.
- 맥북 정리: k3d 클러스터와 k3d LocalStack은 지웠다. 이미지 gyeol/*:20261116153743(약 2.8GB)은 다음 k3d-up 때 지워진다.

### 6. 트러블슈팅
1. helm `--wait` 시간 초과(위). 원인은 새 클러스터의 시스템 이미지 받기다. 기다리니 해결됐다.
2. 시험 데이터 넣기 첫 시도에서 `title_alias.method` NOT NULL에 걸렸다(내 SQL 실수). 고쳐서 다시 넣었다.
3. 개발 api·worker·fetcher를 빌드·k3d 동안 멈췄다가 다시 켰다. 다시 켤 때 worker는 LLM_MODE=fake, MAP_ADMIN_ENDPOINT=true였다. 지금 dev-status는 7개 모두 ✓이고, 리뷰 방·사용자 방 2개는 ACTIVE 그대로다.

## 3단계 결과·4단계 준비 (2026-11-16)

### 배포 3단계(CI·이미지) + 4단계(서버 Terraform) 준비 보고 (11-17, 갈래 1)

커밋·push·apply·시크릿 등록은 하지 않았다. 유료 호출은 0이다.
AWS는 읽기만 했다: 상태 조회, plan, 스팟 가격·VPC·OIDC 공급자 조회.

### 결론
1. **이미지 저장소는 GHCR을 그대로 쓴다(11-14 결정, 구현·시험 끝).** ECR은 만들지 않는다.
   - `release.yml`이 태그마다 arm64 이미지 5개를 GHCR 공개 패키지로 올린다. `v0.1.0-rc1`에서 익명 pull까지 확인했다.
   - GHCR 공개 패키지는 저장·전송 무료다. ECR은 저장 GB당 월 약 0.10달러이고(추정), 서버에 ECR 로그인 도우미와 IAM, 수명 규칙 Terraform이 더 필요하다.
2. **GitHub Actions:** 태그 push → 검사 → 이미지 → **배포(새로)** + Release.
   - 배포는 `deploy.yml`이다. OIDC로 `gyeol-deploy` 역할을 맡고, SSM으로 서버의 `gyeol-deploy <태그>`를 실행한다.
   - 롤백은 같은 workflow를 손으로 실행한다(입력: 태그).
   - 시험판 태그(하이픈)는 배포하지 않는다.
3. **embed는 운영에 필요하다.**
   - 방에 처음 담긴 가수를 지도에 올리는 경로가 embed를 쓴다: `MapService.prepare` → 지식 → 설명 → `embedArtists` → 이웃 평균 자리.
   - 곡 보강(`Enrich.embedTracks`)도 embed를 쓴다.
   - 빼면 새 가수가 지도에 자리를 잡지 못한다.
   - 메모리 819Mi(11-16 측정)라 4GiB 서버 계획에 이미 들어 있다. replicas 1, HPA 끔(values-single) 그대로 둔다.
4. **서버 Terraform(`infra/terraform/single`)을 만들었다.** plan은 **14 to add**다. 비용은 1주 약 3.7달러(부가세 포함 약 4.1달러)이고, 월로는 약 17달러다(5절).
5. **D4 절차:** `docs/todo/d4-map-data.md`에 정리했다. 로컬 임시 DB에서 복원까지 시험해 통과했다(1,480 자리 일치).

### 1. 바꾼·만든 파일
| 파일 | 내용 |
|---|---|
| `.github/workflows/deploy.yml` (새) | workflow_call·workflow_dispatch. Environment `production`, OIDC → `aws ssm send-command` → 결과 대기(최대 약 28분) → 밖에서 `https://gyeol.blog/` 200, `/api/rooms/healthcheck` 404(JSON) 확인 |
| `.github/workflows/release.yml` | images 뒤 `deploy` job(하이픈 태그 제외). 머리 주석 |
| `.github/workflows/checks.yml` | `infra/terraform/single` validate, `gyeol-deploy.sh` 문법, 운영 모양 helm template |
| `deploy/helm/gyeol/templates/ingress.yaml` | `/api`·`/` Ingress 분리. TLS(certresolver `le`, websecure). 관리자 Ingress(`/api/admin`·`/admin`) + `ipAllowList` 미들웨어. `/api` 요청 수 제한 미들웨어 |
| `values.yaml` | `ingress.tls`, `admin.restrict/allowedIps`, `rateLimit`(기본 모두 끔이라 k3d는 그대로) |
| `values-single.yaml` | tls·admin.restrict·rateLimit 켬 |
| `infra/terraform/single/*` (새) | `main`·`variables`·`network`·`ec2`·`iam`·`deploy`·`outputs`.tf, `user-data.sh.tftpl`, `values-prod.yaml.tftpl`, `gyeol-deploy.sh`, 예시 2개, `.terraform.lock.hcl` |
| `docs/todo/d4-map-data.md` (새) | 지도 데이터 옮기기 절차와 로컬 시험 결과 |

- 맥북에만 있는 파일(git 제외): `infra/terraform/single/backend.hcl`(base와 같은 상태 버킷), `terraform.tfvars`(plan용 임시값)

### 2. single plan 자원 (14)
- 네트워크
  - `aws_default_subnet`(ap-northeast-2d)
  - 보안 그룹 + 80·443 들어옴 + 나감 전부
  - 탄력적 IP
- 서버
  - `aws_instance`: t4g.medium **스팟 persistent·회수 시 stop**
  - Ubuntu 24.04 arm64(ami-093a68505ea068a90)
  - IMDSv2 홉 2, gp3 30GB 암호화, 종료 시 삭제 안 함
  - user-data 5.2KB(한도 16KB), `bash -n` 통과
- IAM
  - 서버 역할 + 인스턴스 프로필
  - 붙이는 정책: base `app_policy_arn`, `AmazonSSMManagedInstanceCore`
  - 인라인 정책: Parameter Store `/gyeol/prod/*` 읽기 + `kms:Decrypt`(ViaService ssm) + 선물 이미지 모델(us-west-2) InvokeModel
- 배포 역할 `gyeol-deploy`
  - 신뢰: 계정에 이미 있는 GitHub OIDC 공급자, `aud=sts.amazonaws.com`, `sub=repo:SANGWOO-JOO/gyeol:environment:production`
  - 권한: 이 인스턴스 + `AWS-RunShellScript`에만 `ssm:SendCommand`, 결과 조회 2개(`*`)
- 출력: `public_ip`, `instance_id`, `github_variables`(아래 4절에 넣을 값), `ami`

### 서버 user-data가 하는 일 (처음 부팅 한 번)
1. swap 2GB를 만든다.
2. AWS CLI v2(arm64)를 설치한다.
3. Traefik HelmChartConfig를 둔다(아래 항목들).
4. k3s `v1.35.5+k3s1`(k3d 검증과 같음)과 helm `v4.3.1`(CI·맥북과 같음)을 설치한다.
5. `/etc/gyeol/values-prod.yaml`과 `/usr/local/bin/gyeol-deploy`를 만든다.

Traefik 설정:
- Let's Encrypt HTTP-01(resolver `le`)
- HTTP→HTTPS 넘김
- acme 저장 볼륨
- `externalTrafficPolicy: Local`

앱 배포는 하지 않는다. 첫 태그 배포가 한다.

### `gyeol-deploy <태그>` (서버, SSM으로 실행)
1. 태그 형식을 확인한다.
2. 배포 전 백업: CronJob으로 Job을 만들고 완료를 기다린다. 첫 배포에는 건너뛴다.
3. GitHub 태그 압축본에서 차트를 받는다.
4. Parameter Store `/gyeol/prod/*` → `gyeol-secrets`. DB_PASSWORD가 없으면 중단한다.
5. `helm upgrade --install -f values-single.yaml -f /etc/gyeol/values-prod.yaml --set image.tag=… --wait 15m`
6. rollout을 확인한다(5개).

비밀값은 출력하지 않는다.

### 3. k3d로 확인한 것 (k3s v1.35.5 번들 Traefik 3.6.13, 앱 없이 whoami)
- HelmChartConfig의 ACME 인자 5개가 들어갔고, acme PVC(local-path)가 Bound였다. `traefik.io` CRD(middlewares 등)가 있다.
- HTTP → 301 → `https://gyeol.blog/`
  - **처음 설정(`to=websecure`)은 내부 포트 8443으로 넘겨 고쳤다(`to=:443`).**
- HTTPS `/` → web, `/api/x` → api. 관리자 경로는 허용 IP가 아니면 `/api/admin`·`/admin` 모두 403이다.
- 요청 수 제한(시험값 burst 5): `/api` 12연속 → 200×4·429×8. 웹 `/`는 200×12로 제한되지 않았다.
- 시험 중 Traefik이 실제 Let's Encrypt에 gyeol.blog 발급을 1번 시도해 실패했다(DNS가 이 클러스터가 아님). 실패 1번이라 영향은 없다고 본다(추정). 시험 클러스터는 지웠다.
- 확인하지 못한 것: 방문자 IP 보존. k3d는 docker 망이라 X-Forwarded-For가 LB 컨테이너 주소였다. 서버에서 첫 배포 뒤 확인해야 한다.

### 4. GitHub에 할 일 (사용자, Claude는 하지 않음)
| 무엇 | 값 | 방법 |
|---|---|---|
| Environment `production` | 배포 브랜치·태그: **태그 `v*` + 브랜치 `main`**(롤백 수동 실행이 main에서 돎). 원하면 Required reviewers에 본인 | Settings → Environments → New environment |
| Variables 3개 | `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`(ap-northeast-2), `SERVER_INSTANCE_ID` | single apply 뒤 `terraform -chdir=infra/terraform/single output github_variables`. Settings → Secrets and variables → Actions → Variables(또는 `gh variable set 이름 --body 값`) |
| Secrets | **없음** | 이미지는 `GITHUB_TOKEN`, AWS는 OIDC |
| GHCR 패키지 공개 | 이미 공개(11-14 rc1 익명 pull 확인) | — |

### 5. 비용 (plan 자원 기준, 서울)
- 스팟 가격(11-17 조회, 최근 7일, t4g.medium): 2d 0.0108~0.0121, 2a 0.0149~0.0160, 2b 0.0146~0.0163, 2c 0.0166~0.0172달러/시간 → **2d 유지**

| 항목 | 1주(168시간) | 월 |
|---|---|---|
| EC2 스팟 2d | 약 1.9~2.0달러 | 약 7.9~8.8달러 |
| EBS gp3 30GB | 약 0.6달러 | 약 2.7달러 |
| 탄력적 IP | 약 0.8달러 | 약 3.6달러 |
| SQS(리스너 17개 대기 요청) | 0~0.3달러(월 100만 건 무료 안쪽일 수 있음) | 약 1.3달러(추정) |
| S3·Parameter Store 표준·서브넷·IAM·OIDC 역할 | 거의 0 | 0.5달러 미만 |
| **합계 / 부가세 포함** | **약 3.7 / 약 4.1달러** | **약 16~17 / 약 18~19달러** |
| 쓴 만큼 | Bedrock(업로드 1회 약 0.25달러), 선물 이미지 하루 최대 10장 × 약 0.04달러 | |

- 내릴 때(1주 뒤): `terraform destroy`(single).
  - **EBS는 남는다**(종료 시 삭제 안 함, S8). 마지막 백업이 S3에 있는지 보고 볼륨을 손으로 지운다. 안 지우면 월 약 2.7달러.
  - 기본 서브넷은 `force_destroy`라 함께 지워진다.
  - base는 남겨도 거의 0이다(SQS를 읽는 서버가 없음, S3 작은 양).

### 6. 4단계 apply 전에 빠진 값·할 일
1. **`terraform.tfvars`(single)**
   - `acme_email`: 지금 plan용 임시값 `admin@gyeol.blog`다. gyeol.blog에는 받는 메일이 없으니 사용자 메일로 바꾼다.
   - `admin_ips`: 집 IP /32. 비우면 관리자 경로가 밖에서 닫힌다.
   - `availability_zone`: 2d
2. **Parameter Store 4개**(사용자 터미널, 값이 기록에 남지 않게):
   ```bash
   read -rs V; aws ssm put-parameter --name /gyeol/prod/DB_PASSWORD --type SecureString --value "$V"; unset V
   ```
   - 같은 방식으로 `ADMIN_TOTP_KEY`(`openssl rand -base64 32`), `LASTFM_API_KEY`, `YOUTUBE_API_KEY`를 넣는다.
3. **SES 샌드박스:** values-prod는 메일 `ses`다(base 출력 mail_from 있음). 샌드박스에서는 인증한 주소에만 가므로, 다른 사람은 방 만들기 인증 메일을 못 받는다. 사용자를 받기 전에 해제 신청이 필요하다.
4. **DNS:** apply 출력 `public_ip`를 Porkbun A 레코드 `gyeol.blog`에 넣는다. `dig +short gyeol.blog`로 반영을 확인한 **뒤에** 첫 태그를 push한다.
   - Let's Encrypt는 검증 실패에 시간당 한도가 있다(추정: 5회).
5. Stability Stable Image Core Marketplace 구독(us-west-2, 콘솔). 안 하면 선물은 기본 캐릭터로 나온다.
6. 첫 배포 → D4(지도 옮기기) → 관리자 계정 만들기(설계 9절 12번) → 사용자 받기

### 7. 설계와 달라진 판단 · 트러블슈팅
- **기본 VPC에 서브넷이 0개였다**(IGW·기본 경로는 있음). plan이 "no matching EC2 Subnet"으로 실패했다.
  - `aws_default_subnet`으로 고른 영역에 기본 서브넷을 만들게 했다. 무료이고 destroy 때 지운다.
- **종료 보호(`disable_api_termination`)는 넣지 않았다.** 스팟 인스턴스에는 쓸 수 없는 것으로 안다(추정).
- **Traefik 넘김 대상 `websecure` → `:443`:** 위 3절.
- **방문자 IP:** `externalTrafficPolicy: Local`을 추가했다. 서버에서 확인이 필요하다.
- **OIDC 공급자:** 계정에 이미 있어 data로 참조한다(다른 프로젝트와 공유). 그래서 single destroy 때 지워지지 않는다.
- **최소 구성이라 이번에 뺀 것**(설계에는 있음, 1주 운영에는 과하다고 판단)
  - S7 Bedrock 예산 자동 조치: Claude 모델 비용이 Cost Explorer에서 "Amazon Bedrock"이 아니라 Marketplace 서비스 이름으로 잡힐 수 있어(추정), Service 필터 예산이 비용을 놓칠 수 있다. base 월 20달러 예산 알림과 앱 하루 한도로 대신한다.
  - 16절 모니터링(CloudWatch·SNS·Lambda·Slack·uptime workflow), `tools/db-tunnel.sh`, 앱 검색 `IpRateLimiter`(15-2)
- **배포 확인 경로:** release 노트의 `/api/health`는 없는 경로다. 없는 `/api` 경로는 404가 아니라 500 INTERNAL을 돌려준다(작은 문제, issues 후보). 그래서 배포 확인은 `/api/rooms/healthcheck` 404 JSON으로 했다.
- `data "aws_subnet"` 조회 실패 외에는 plan 오류가 없다. `terraform validate`·`fmt -check`·`helm lint`(기본·local·single)·workflow YAML 파싱도 모두 통과했다.
