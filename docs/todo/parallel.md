# 병렬 작업 관리 (2026-11-13~)

음악 방 ③~⑥과 웹 화면을 세 갈래로 나눠 동시에 진행한다. 설계 근거는 `room-plan.md`, `room-api-db.md`.

## 0. 이 파일을 쓰는 규칙

- **이 파일은 한 곳에만 있다:** `~/Desktop/github/gyeol/docs/todo/parallel.md` (원래 폴더).
  - worktree 안의 사본은 보지도 고치지도 않는다. 항상 이 절대 경로로 읽고 쓴다.
- **원래 폴더에서 일하는 세션(갈래 1)만 이 파일을 직접 고친다.** worktree 세션(A·B)은 훅 때문에 이 파일을 고칠 수 없으므로, 적을 내용을 관리 세션에 메시지로 보내고 관리 세션이 대신 적는다(11-13 사용자 결정).
- **자기 줄·자기 절만 고친다.** 1절 표는 자기 갈래 줄만, 3·4절은 자기 이름을 붙여 줄을 더한다.
- **상태가 바뀔 때마다 갱신한다:** 시작, 넘겨줄 것 준비, 테스트 시작·끝, 검토 요청.
- **작업 중에는 커밋하지 않는다.** 합칠 때 사용자가 한 번 커밋한다.
- **git 명령은 사용자가 직접 실행한다.** 세션은 명령을 안내만 한다.
  - push, merge, 브랜치 삭제는 하지 않는다.
  - 커밋 메시지에 Co-Authored-By 줄을 넣지 않는다.
- **조율은 "AWS 계정 컨텍스트 및 EKS 학습" 세션(관리)이 맡는다.** 막히면 이 파일 5절에 적고 사용자에게 알린다.
- **자동 진행(11-13 사용자 지시):** 갈래 A의 3~9단계는 관리 세션이 단계마다 점검(`web/`만 변경, tsc·vitest 통과, 화면 확인, 비밀값 없음)하고 통과하면 사용자 승인 없이 커밋한 뒤 다음 단계를 지시한다. push는 9단계 뒤 한 번. 기존 동작을 바꾸지 않는 백엔드 추가(필드·새 API)도 테스트 통과 시 자동 커밋. 비용이 드는 일·기존 동작 변경·디자인 방향 변경은 멈추고 사용자에게 남긴다.
- **이슈 기록:** 문제와 처리 결과는 `docs/todo/issues.md`에 적고, 결정이 필요하면 추천안으로 진행한 뒤 그 사실을 적는다.
- **사용자는 관리 세션에서만 지시·결정한다(11-13).** 갈래 세션은 결과·설계안·결정 사항을 관리 세션에 보고하고, 사용자에게 git 명령을 직접 안내하지 않는다. git 순서는 관리 세션이 안내한다.

## 1. 갈래

| 갈래 | 세션 | 브랜치 · 폴더 | 범위 | 상태 | 마지막 갱신 |
|---|---|---|---|---|---|
| 1 | 작업 세션 | `main` · 원래 폴더 | ④ 선물(V24) → ⑤ 놀이터(V25) → ⑥ 관리자(V26) | 합침: ⑥ 커밋됨(0598dd6), 갈래 1 범위 끝 | 11-13 |
| A | 음악 방 웹 화면 (관리 세션의 포크) | `main` · 원래 폴더 (`web/`만, 11-13 결정) | 웹 화면 전부(방·선물·놀이터·관리자·지도). 범위는 `room-web.md` 4절 1~9단계 | 합침: 2~9단계 모두 커밋(마지막 `a9699dd`), tsc·vitest 73·vite build 통과. 범위 끝 | 11-13 |
| B | MailSender 분리 및 SES 구현 | `claude/mailsender-ses-setup-26d617` · `.claude/worktrees/mailsender-ses-setup-26d617` | ③ 메일: `MailSender`를 `api/mail`로 이동, `SesMailSender`·`MailTemplates`(CREATE·HOST_RESET), `MAIL_MODE`(log 기본·ses), Terraform `ses.tf`(`mail_domain` 비면 자원 없음)·IAM `ses:SendEmail` | 합침: `41d74e4`가 main에 들어감(fast-forward, push 완료), api 33 통과. 남은 것은 5절(도메인 등) | 11-13 |

상태 값: 대기 · 진행 · 검토 요청 · 합침 · 멈춤(5절에 사유).
기준 커밋: `7723fb8` (Terraform bootstrap·base까지 포함).

## 2. 파일 소유

| 영역 | 주인 | 비고 |
|---|---|---|
| `backend/core/src/main/resources/db/migration/` V24~V26 | 1 | 다른 갈래는 마이그레이션을 만들지 않는다. B가 표가 필요하면 4절에 요청 |
| `backend/*/room/` (Room*Controller·Repository·Service), worker 큐 리스너 | 1 | |
| `backend/api/.../room/RoomSupport.kt` | 1 | **겹침 주의:** 지금 `MailSender`·`LogMailSender`가 이 파일 안에 있다. B는 메일 코드를 새 파일(예: `api/mail/`)로 옮기는 변경만 하고, 옮긴 사실을 4절에 적는다. 1은 그동안 이 파일의 메일 부분을 고치지 않는다 |
| `web/` | A | 다른 갈래는 고치지 않는다 |
| `infra/terraform/` | B | 기존 `base`에 SES·IAM을 더한다. `apply`는 사용자 확인 후 |
| `backend/*/src/main/resources/application*.yml` | 공유 | 고치기 전에 3절에 "누가·어느 키" 적기 |
| `local/localstack/init-aws.sh`, `deploy/helm/gyeol/` | 공유 | 새 큐·값을 더할 때 3절에 적기 |
| `docs/todo/room-api-db.md` | 1 | 구현 기록 절은 각자 자기 절만 더한다. **절 번호 고정(11-13):** 9 ④ 선물 · 10 ③ 메일(B) · 11 ⑤ 놀이터 · 12 ⑥ 관리자 |
| `README.md`, `CLAUDE.md` | 관리 | 갈래는 고치지 않는다 |

## 3. 공용 자원

한 번에 한 세션만 쓴다. 쓰기 시작할 때 "사용 중"에 이름과 시각을 적고, 끝나면 지운다.

| 자원 | 사용 중 | 메모 |
|---|---|---|
| 백엔드 Gradle 테스트 (`./gradlew …test`) | — | 메모리 16GB. 둘이 동시에 돌리면 멈출 수 있음 |
| 개발 서버 (`scripts/dev-up.sh`, 포트 15432·4566·18080~18084·15173) | — | 원래 폴더에서 이미 켜져 있음(11-13). worktree에서 다시 켜지 않는다. A는 이 서버의 API를 쓰고, 웹 개발 서버만 다른 포트로 띄운다 |
| Bedrock (`LLM_MODE=bedrock`) | — | 비용 발생. 쓰기 전에 사용자 확인 |
| AWS 계정 (Terraform `plan`·`apply`, SES) | — | `plan`은 B가 실행 가능, `apply`·콘솔 작업은 사용자 확인 후 |

공유 파일 변경 기록:

| 날짜 | 갈래 | 파일 · 키 | 내용 |
|---|---|---|---|
| 11-13 | 1 | `backend/api/.../application.yml` · `gyeol.room.gift-queue` | ④ 선물 열기 큐 이름 |
| 11-13 | 1 | `backend/worker/.../application.yml` · `gyeol.worker.gift.*` | ④ 선물 큐·실패 큐·이미지 모델·리전·하루 예산 |
| 11-13 | 1 | `local/localstack/init-aws.sh` · `gyeol-local-gift-image`(+dlq) | ④ 선물 이미지 큐. 캐릭터 이미지는 기존 covers 버킷 `characters/` 아래(새 버킷 없음) |
| 11-13 | 1 | `backend/api/.../application.yml` · `gyeol.room.code-match-queue`, `gyeol.room.code-match.*` | ⑤ 코드 맞추기 큐·하루 30번·최소 5곡·점수 비중 |
| 11-13 | 1 | `backend/worker/.../application.yml` · `gyeol.worker.code-match.*` | ⑤ 이유 큐·실패 큐 |
| 11-13 | 1 | `local/localstack/init-aws.sh` · `gyeol-local-code-match`(+dlq) | ⑤ 이유 쓰기 큐 |
| 11-13 | 1 | `backend/api/.../application.yml` · `gyeol.admin.*` | ⑥ 관리자: TOTP 키(ADMIN_TOTP_KEY), 쿠키 Secure, 세션·잠금 시간, IP 분당 제한 |
| 11-13 | 1 | `backend/api/.../application-local.yml` · `gyeol.admin.totp-key` | ⑥ 로컬 전용 가짜 키(커밋 가능, 운영 키 아님) |
| 11-14 | 1 | `backend/api·worker/.../application.yml` · `spring.cloud.aws.sqs.queue-not-found-strategy: fail` | I-1: 앱이 없는 큐를 만들지 않음 |
| 11-14 | 1 | `infra/terraform/base/sqs.tf`, `deploy/helm/gyeol/values*.yaml`·`templates/backend.yaml` | I-1 추가: 음악 방 큐 3쌍, 선물 하루 한도 GIFT_DAILY_IMAGE_LIMIT |
| 11-14 | 1 | `backend/api·worker/.../application.yml` · gift daily-image-limit | 선물 하루 새 그림 10장(기본값), api도 같은 환경 변수 |
| 11-14 | 1 | `local/localstack/init-aws.sh` 전체, `local/compose.yaml` localstack 상태 검사, `scripts/dev-up.sh`·`scripts/k3d-up.sh` `up -d --wait` | I-1: 멱등 초기화·준비 표시 파일·DB·LocalStack이 준비될 때까지 대기 |
| 11-13 | B | `backend/api/.../application.yml` · `gyeol.mail.mode`·`from`·`from-name`·`configuration-set` | ③ 메일 설정. `gyeol.room.*`는 안 건드림 (관리 세션 기록) |
| 11-13 | B | `deploy/helm/gyeol/templates/backend.yaml` · api env `MAIL_MODE`·`MAIL_FROM`·`MAIL_CONFIG_SET` | ③ 메일 (관리 세션 기록) |
| 11-13 | B | `deploy/helm/gyeol/values.yaml` · `api.mail.{mode: log, from, configurationSet}` | ③ 메일 (관리 세션 기록) |

## 4. 넘겨줄 것

| 날짜 | 보내는 쪽 → 받는 쪽 | 내용 | 확인 |
|---|---|---|---|
| 11-13 | 1 → A | ① 방·참가·담기 API는 이미 있음 (`room-api-db.md` 7절) | — |
| 11-13 | 1 → A | ④ 선물 API 준비됨: `GET /me`의 `gift` 요약, 담기 201의 `gift`, `GET/POST /me/gifts…`, `PUT /me/avatar`, `GET /characters/{id}/image`, `MemberView.avatarCharacterId` (`room-api-db.md` 9절). 개발 서버에 반영됨(fake: 기본 그림) | |
| 11-13 | 1 → A | ⑤ 코드 맞추기 API 준비됨: `POST /api/rooms/{rid}/code-match {a,b}`(200 저장 결과·202 새 계산), `GET …?a=&b=`, 응답의 `confidence`(LOW·MEDIUM·HIGH)·`stale`·`status`(REASONING이면 몇 초마다 GET). 409 `NOT_ENOUGH_SONGS`(각자 5곡), 429 `CODE_MATCH_LIMIT` (`room-api-db.md` 11절). 개발 서버 반영됨(fake 이유) | |
| 11-13 | 1 → A | ⑥ 관리자 API 준비됨(웹 7-a~7-c): `/api/admin/login`→`/login/otp`(쿠키), `/me`, `/rooms`, `/rooms/{rid}`, 숨김·되살리기·이름·닉네임·내보내기, `/audit` (`room-api-db.md` 12절). 로컬 계정은 `local/admin-dev-seed.sql`(가짜 값, 개발 DB에 넣어 둠). ⑤ 응답에 `sharedArtistCount`·`sharedGalaxyCount` 추가 | |
| 11-13 | B → 1 | `MailSender`·`LogMailSender`를 `dev.gyeol.api.mail`(`backend/api/.../api/mail/`)로 옮김. `RoomSupport.kt`는 메일 부분만 삭제, `RoomDraftController`·`RoomApiIntegrationTest`는 import만 변경, 시그니처 그대로. B 브랜치에만 있고 아직 합치지 않음 (관리 세션 기록) | |

## 5. 확인 필요 (사용자)

| 날짜 | 갈래 | 내용 | 결과 |
|---|---|---|---|
| 11-13 | B | ① 발신 도메인 결정·등록 | **보류:** 도메인 없이 코드와 `mail_domain=""` Terraform만 먼저(11-13 결정) |
| 11-13 | B | ② base `terraform apply` (plan 확인 후) | 도메인 정한 뒤 |
| 11-13 | B | ③ DNS 반영과 DKIM 인증 확인 | 도메인 정한 뒤 |
| 11-13 | B | ④ SES 샌드박스 해제 신청 (콘솔) | 도메인 정한 뒤 |
| 11-13 | B | ⑤ 해제 전 테스트용 받는 사람 주소의 인증 메일 링크 클릭 | 도메인 정한 뒤 |
| 11-13 | B | `HOST_RESET` 메일 문구를 지금 같이 만들지 | **같이 만듦**(11-13 결정, A의 6-d 화면이 씀) |
| 11-13 | A | `room-web.md` 범위표 확정 (지도 화면은 Codex, 관리자 화면은 ⑥ 뒤) | **확정**: Q1~Q4 모두 추천안 |
| 11-13 | A | `docs/design/ci/`·`hero/` 커밋 여부 (worktree에서 보이려면 필요) | **커밋함**(A 시작 전), `map-v1-300.png` 제외 |
| 11-13 | 1 | 로컬 LocalStack이 19:26에 다시 켜질 때 `init-aws.sh`가 중간에 멈춤(이미 있는 큐의 VisibilityTimeout이 달라 `QueueAlreadyExists`). 그래서 **버킷 `gyeol-local-uploads`·`covers`, 큐 `guess-dlq`·`artist-image-dlq`가 없었음**. 갈래 1이 `covers` 버킷과 `gift-image` 큐만 손으로 만듦. 업로드(Takeout) 확인 전에 LocalStack 초기화를 다시 돌려야 함 | |

## 6. 합치는 순서 (예정)

1. **B:** 메일 코드 이동 + SES 구현. 백엔드 변경이 작아서 먼저 합치면 1의 충돌이 줄어든다.
2. **1:** ④ → ⑤ → ⑥. 각 단계 끝마다 `main`에 커밋한다. 이미 `main`에서 일한다.
3. **A:** 화면 단위로 합친다. `web/`만 고치므로 순서 제약이 적다.

각 합침 전에 해당 갈래가 테스트 결과를 4절이나 1절 상태에 적는다. 관리 세션이 충돌·누락을 확인한 뒤, 사용자가 git 명령을 실행한다.

## 7. 기록

| 날짜 | 내용 |
|---|---|
| 11-13 | 파일 생성. 기준 커밋 `7723fb8` |
| 11-13 | 갈래 1: ④ 구현 뒤 개발 서버 api·worker를 새 jar로 다시 켬(V24 적용) |
| 11-13 | 갈래 1: ⑤ 구현 뒤 개발 서버 api·worker를 새 jar로 다시 켬(V25 적용), LocalStack에 code-match 큐를 손으로 만듦 |
| 11-13 | 갈래 1: ⑥ 구현 뒤 개발 서버 api·worker를 새 jar로 다시 켬(V26 적용), 로컬 가짜 관리자 계정을 개발 DB에 넣음 |
| 11-13 | 기록 방식 변경: worktree 세션은 관리 세션이 대신 기록. 갈래 B 시작(설계 검토 완료) |
| 11-13 | 관리 점검: 갈래 1(④, main에 스테이징됨)과 B가 함께 고친 파일 4개. `RoomDraftController`·api `application.yml`·`RoomApiIntegrationTest`는 서로 다른 줄이라 자동으로 합쳐질 것으로 예상. **`room-api-db.md`는 둘 다 끝에 `## 9.`을 더해 충돌** → ④가 9절, B는 10절로 바꾸기로 함 |
| 11-13 | 합치는 순서 변경: ④(main) 먼저 커밋 → B를 그 위로 맞춘 뒤 합침 |
| 11-13 | ④ `033f48b`, ③ `41d74e4` main에 합침·push (관리 세션이 사용자 지시로 실행) |
| 11-13 | 자동 진행 끝: 웹 2~9단계와 백엔드 추가(codeExpiresAt·host-reset·검색 잡음·genreMix·가짜 이유 조사) 모두 커밋, 한 번에 push. 남은 결정 I-10·I-19, 미확인 화면 I-12·I-16·I-18·I-23 (`issues.md`) |
| 11-13 | 자동 진행: host-reset `2099a96`, A 3단계 `fde5e2d` 커밋(push 보류). 갈래 1에 Last.fm 잡음 제거(I-11) 지시 |
| 11-13 | `codeExpiresAt` `4552764` 커밋·push(api 46 통과). **방장 암호 재설정(host-reset) API가 서버에 없음** → 6-d 화면 보류, 백엔드 구현 필요 |
| 11-13 | A 2단계 `7bc6209` 커밋·push. 갈래 1에 `codeExpiresAt` 추가 지시(room-drafts·resend, host-reset 포함) → 끝나면 A가 웹 계산을 그 필드로 교체. 원래 폴더에서 1은 `backend/`, A는 `web/`만 고침 |
| 11-13 | A 1단계 설계 승인: 원래 폴더에서 `web/`만, 첫 화면 새 디자인(기존 기능은 작은 링크), CI 다크는 첫 화면·방·관리자(청취 분석·곡 고르기는 기존 테마), 단계마다 보고 → 승인 → 관리 세션이 `web/`만 커밋 |
| 11-13 | 지도 화면 담당 변경: Codex → Claude(갈래 A 마지막 단계). `room-web.md` Q1·4절, `room-plan.md` 9절 ② 수정 |
| 11-13 | ⑥ 설계 승인: 5번 실패 15분 잠금, 감사 기록 1년 보관(방 삭제 시 IP·닉네임은 지움, room_id SET NULL), TOTP 비밀값 AES-GCM, 계정 도구 tools/admin-account(사용자가 직접 실행), ⑤ 응답에 sharedArtistCount·sharedGalaxyCount 추가. 갈래 1에 전달 |
| 11-13 | ⑤ `7d3824b` main에 커밋·push(api 39·worker 72 통과). 5~9곡 표시는 "라"(숫자 없이 겹친 것과 이유만, 10곡부터 %) → `room-web.md` 8-b 규칙 |
| 11-13 | ⑤ 결정: 각자 5곡부터 + 신뢰도 표시 + 곡 수별 점수 안정성 측정(5·10·20곡), 비중 40·30·20·10(설정값), 방당 하루 30번, 이유에 가수 이름 허용, "둘을 이어 주는 곡"은 이번 범위 제외. 갈래 1에 전달 |
