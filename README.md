# 결 (gyeol) — 친구와 함께 만드는 음악 지도, 근거가 붙은 취향 분석

> A shared music galaxy you build with friends, plus listening-history analysis and recommendations — with evidence for every claim.

**링크 하나로 친구들과 함께 만드는 음악 지도.** 각자 좋아하는 노래를 담으면, 비슷한 가수끼리 가까이 놓인 "음악 은하" 위에 행성과 위성으로 쌓입니다. 두 사람을 골라 취향이 얼마나 맞는지(코드 맞추기) 근거와 함께 보여 주고, 50곡마다 담은 노래로 캐릭터를 만들어 줍니다.
유튜브 청취 기록(Google Takeout)을 올리면 **많이 들은 곡·가수·시간대를 원곡 기준으로 분석**하고, 좋아하는 곡 몇 개를 고르면 위키백과·MusicBrainz·Last.fm **근거와 함께** 결이 맞는 곡 20곡을 추천합니다.
EKS·AI 경험을 실측 수치와 함께 쌓으려고 만든 사이드 프로젝트입니다.

**서비스: https://gyeol.blog** (지인 대상 운영 기간에만 열어 둡니다. 비용을 줄이려고 스팟 서버를 써서 가끔 몇 분 끊길 수 있습니다.)

| 진행 상황 | |
|---|---|
| 로컬 개발 L1~L3 (업로드·통계·곡 식별·RAG 보강·추천·평가) | 완료 |
| 실제 Takeout으로 청취 분석 품질 개선 (식별·표지·순위) | 완료 |
| 음악 방 (방·참가·담기, 음악 지도 데이터, 메일, 50곡 선물, 코드 맞추기, 관리자, 웹 화면·지도) | 완료 (메일: SES gyeol.blog 인증 완료, 프로덕션 승인 대기) |
| 지인 대상 공개 시험 (Cloudflare Quick Tunnel, 11-15~16, 실제 AI 모드) | 완료 |
| 운영 배포 (gyeol.blog, k3s 단일 서버·스팟 t4g.medium, Terraform·Helm·GitHub Actions OIDC→SSM, Let's Encrypt) | 완료 (11-16 첫 배포, 지도 데이터 옮김) |
| 로컬 쿠버네티스(k3d) 배포 검증 | L3-3까지 완료, 이후 변경 확인 예정 |
| AWS EKS (Terraform, 측정하는 날만 올리고 내리는 회차 5번) | 진행 예정 |
| L4 (대화 에이전트, MCP 서버) | 보류 |

## 무엇을 하나

| 입구 | 입력 | 결과 |
|---|---|---|
| **음악 방** (첫 화면) | 방장이 이메일 인증으로 방을 만들고 초대 링크 공유, 최대 5명이 닉네임 + 비밀번호 4자리로 참가해 노래를 검색해 담음 | 음악 은하 지도, 코드 맞추기, 50곡 선물 캐릭터 |
| 곡 고르기 | 곡 3~5개 검색해서 선택 | 추천 20곡과 이유 |
| 청취 기록 올리기 | Takeout `watch-history.json` | 많이 들은 곡·가수(원곡 기준, 페이지로 전체 보기)·시간대·기간 |

**음악 방**
- **음악 은하 지도:** 한·일·미 인기 가수 1,458명을 근거 기반 가수 설명 벡터 → UMAP 2D로 미리 배치한 공용 지도에서, 방에 담긴 가수만 행성으로 보여 줍니다. 담은 노래는 사람 색 위성, 여럿이 같이 담은 노래는 가장 안쪽 금빛 궤도. 지도에 없는 가수는 담는 순간 근거를 모아 이웃 평균 자리에 새로 올립니다.
- **코드 맞추기:** 두 사람의 장르 구역 비율·지도 거리·겹친 가수·겹친 노래로 점수를 SQL로 계산하고, LLM은 근거 안에서 이유만 씁니다. 곡이 적으면 점수가 낮게 치우치는 것을 측정해 5~9곡은 % 없이 겹친 것만 보여 줍니다.
- **50곡 선물:** 담은 노래가 50곡 단위에 처음 닿으면 상자 → 구역 비율로 LLM이 그림 묘사 → 이미지 모델이 캐릭터 생성(하루 예산 초과·실패 시 기본 캐릭터). 사람·방당 하루 1개.
- **보안:** 방마다 32바이트 열쇠(HttpOnly 쿠키 + 헤더), 비밀번호 4자리 BCrypt·5회 실패 10분 잠금, 방장 비밀번호는 이메일 코드로 재설정(다른 기기 열쇠 폐기), 관리자는 비밀번호 + OTP 2단계·감사 기록. 관리자도 방장 이메일 전체와 노래 목록은 보지 않습니다.

**공통**
- 계정 없이 무작위 토큰 링크로 접근, 즉시 삭제 버튼, 청취 기록 원본 7일·결과 30일 뒤 자동 삭제 (음악 방은 방장이 지울 때까지 보관하되 1년간 쓰지 않으면 숨김, 숨긴 뒤 7일 뒤 완전 삭제). 메일 기록·관리자 IP는 90일, 방 열쇠 쿠키는 180일
- 이용약관(`/terms`)·개인정보 처리방침(`/privacy`)을 화면에 두고, 올리기 전에 "동의하고 올리기"로 동의를 받습니다. 글꼴은 직접 호스팅합니다(외부 글꼴 요청 없음)
- **원본 파일은 서버로 보내지 않습니다.** 브라우저(Web Worker)에서 음악 기록만 골라 올리고, 일반 영상 제목은 서버에 오지 않습니다.

## 화면

<details open>
<summary><b>휴대폰 화면</b> (9장)</summary>

| 첫 화면 | 방 만들기 | 초대받아 참가 |
|:---:|:---:|:---:|
| <img src="docs/images/mobile-landing.webp" width="240" alt="첫 화면"> | <img src="docs/images/mobile-create.webp" width="240" alt="방 만들기 1단계. 부를 이름·우주 이름·비밀번호 4자리·이메일"> | <img src="docs/images/mobile-join.webp" width="240" alt="초대 링크로 연 참가 화면. 닉네임과 4자리 비밀번호, 이미 참가한 사람 이름"> |
| **방 지도** | **검색해 담기** | **내 노래** |
| <img src="docs/images/mobile-map.webp" width="240" alt="방 지도. 가수 얼굴 행성 주위로 사람 색 위성과 같이 담은 노래의 금빛 궤도가 보인다"> | <img src="docs/images/mobile-search.webp" width="240" alt="가수 카드와 노래 검색 결과, 담기 버튼"> | <img src="docs/images/mobile-mine.webp" width="240" alt="내 노래 목록과 다음 선물까지 남은 곡 수"> |
| **코드 맞추기** | **방장 비밀번호 복구** | **새 캐릭터** |
| <img src="docs/images/mobile-code-match.webp" width="240" alt="코드 맞추기 결과. 점수와 항목별 근거, LLM이 근거 안에서 쓴 이유"> | <img src="docs/images/mobile-host-reset.webp" width="240" alt="방장 비밀번호를 이메일 코드로 재설정하는 화면"> | <img src="docs/images/mobile-gift-character.webp" width="240" alt="담은 노래로 만든 새 캐릭터와 장르 섞기"> |

</details>

<details>
<summary><b>PC 화면</b> (방 전체·가수 자세히·관리자 4장)</summary>

**PC 배치** — 왼쪽 지도, 오른쪽 판(놀이터·검색·내 노래·참가자)

| 참가자 한 명 강조 | 가수 자세히 (설명·출처) |
|:---:|:---:|
| <img src="docs/images/pc-room.webp" alt="참가자 패널에서 한 사람을 골라 그 사람이 담은 노래만 지도에서 강조한 모습"> | <img src="docs/images/pc-artist.webp" alt="가수 판. 근거 기반 설명과 출처 링크, 비슷한 아티스트"> |
| **관리자 방 상세 (감사 기록)** | **관리자 OTP 2단계** |
| <img src="docs/images/pc-admin.webp" alt="관리자 방 상세. 참가자·숨김·조치 사유·감사 기록, 이메일은 가려서 표시"> | <img src="docs/images/pc-admin-otp.webp" alt="관리자 로그인 OTP 2단계 화면"> |

</details>

## 아키텍처

<details open>
<summary><b>전체 시스템 구조 — EC2 한 대(k3s) + AWS 관리형 서비스</b></summary>

<img src="docs/images/architecture.png" alt="시스템 구조. 사용자 → Porkbun DNS → EIP → EC2 스팟 k3s(Traefik·web·api·worker·fetcher·embed·PostgreSQL), SQS·S3·SES·Bedrock·Parameter Store, GitHub Actions → OIDC → SSM 배포">

</details>

<details>
<summary><b>인프라 코드(IaC) — Terraform으로 서버 생성부터 k3s 설치까지 자동화</b></summary>

<img src="docs/images/terraform.png" alt="Terraform 흐름. base(SQS·S3·SES·IAM·Budget)와 single(EC2 스팟·EIP·보안 그룹·IAM 역할) apply 뒤 EC2 첫 부팅 때 user-data가 swap·AWS CLI·Traefik 설정·k3s·Helm·배포 스크립트를 준비">

</details>

<details>
<summary><b>배포·운영 — Helm 차트와 태그 push 자동 배포(CI/CD)</b></summary>

<img src="docs/images/k3s.png" alt="k3s 운영 구조. Terraform·user-data로 k3s·Helm·Traefik 설치, namespace gyeol의 Deployment 5개·StatefulSet·CronJob 백업·Secret, gyeol-deploy 6단계">

</details>

<details>
<summary><b>AI 파이프라인 — RAG·임베딩·LLM을 근거 안에서만 쓰기</b></summary>

<img src="docs/images/pipeline.png" alt="AI 파이프라인. 가수 근거 수집 → LLM 설명(가드) → e5 임베딩 → pgvector → UMAP 지도, LLM 보조 작업(곡 제목 정리·코드 맞추기 이유·선물 그림)">

</details>

<details>
<summary><b>데이터 모델(ERD) — 음악 방 핵심 테이블 18개</b></summary>

<img src="docs/images/erd.png" alt="ERD. 방·멤버(room, member, member_key, room_song, song_request), 곡 카탈로그(artist, track), 지도·RAG(artist_profile, knowledge_chunk, artist_vector, map_version, galaxy, artist_position), 선물·코드 맞추기(gift_box, gift_character, code_match), 운영(llm_call)">

</details>

## 측정한 수치

| 항목 | 결과 | 비교 기준 |
|---|---|---|
| 곡 식별 정확도 | 28/29 | 정답 세트 29곡 |
| LLM 제목 정리 | 28/28 | 규칙만 4/28 |
| 곡 검색 (초성·오타·붙여쓰기 포함) | 33/33, 가로채기 0/10 | 표본 33개 |
| 같은 곡이 있는 두 번째 세션 식별 | 0.4초 | 전역 캐시 |
| 곡 설명의 근거 일치율 | RAG 켬 80% | RAG 끔 13% |
| 추천 이유 근거 일치 | Haiku 4.5 19/20 | Sonnet 4.6 20/20 (비용 약 3배, 시간 1.6배) |
| 임베딩 (ONNX fp32) | PyTorch와 같은 벡터, 문장당 8.4ms | — |
| 추천 좋아요 비율 (첫 측정, 1세션 20곡) | 75% | RAG 켬 7/10, 끔 8/10 (표본이 작아 차이 없음) |
| 실제 Takeout 곡 식별 (상위 300개) | 찾음 272, 오류 0 | 개선 전 166, 오류 31 |
| 재생 기준 원곡 확인 비율 | 52.2% | 개선 전 32.0% |
| 상위 10곡 확인 | 약 50초 | 개선 전 처리 후반 |
| 업로드 → 분석·표지 완료 | 20분 17초 | MusicBrainz 초당 1회 제한 |
| 음악 지도: Last.fm "비슷한 가수" 쌍이 지도 이웃 10명 안 (1,458명) | 384차원 16%, 2D 13% | 무작위 1% (약 13~16배) |
| 음악 지도: 2D 이웃 5명 중 같은 나라 | 80% | — |
| 가수 벡터 구성 (157명 검증) | 원문 0.5 + RAG 설명 0.5 | 원문만·설명만보다 이웃 일치가 높아 선택 |
| 음악 지도 만들기 비용 (Haiku 4.5, 1,458명) | 약 $2.50 | 설명 915회 + 구역 이름 27회 |
| 코드 맞추기 점수 안정성 (40곡 두 사람에서 무작위로 뽑아 30회) | 5곡 42.5±15.1 · 10곡 59.4±10.9 · 20곡 70.0±4.3 | 40곡 전체 78점 → 5~9곡은 % 숨김 |

측정 방법과 한계는 설계 문서의 각 단계 끝에 적었습니다. 실제 Takeout 측정은 모두 1회이며 [docs/todo/analysis-latency-errors.md](docs/todo/analysis-latency-errors.md)에, 음악 지도와 코드 맞추기 측정은 로컬 1회이며 [docs/todo/room-plan.md](docs/todo/room-plan.md) 8절과 [docs/todo/room-api-db.md](docs/todo/room-api-db.md) 11절에 정리했습니다.

## 기술 선택

| 영역 | 선택 | 이유 |
|---|---|---|
| 백엔드 | Kotlin 2.3, Spring Boot 4, JDK 21, Gradle 멀티 모듈 | `core`·`api`·`worker`·`external-fetcher` 분리, worker는 역할(큐)별로 켬 |
| 비동기 | SQS + 실패 큐 | 업로드 폭증·외부 API 한도를 큐 길이로 흡수, 재시도·실패 표시 |
| DB | PostgreSQL 17 + pgvector(HNSW) + pg_trgm + fuzzystrmatch, Flyway | 벡터 검색과 한국어 부분·오타 검색을 한 DB에서 |
| LLM | Bedrock Claude Haiku 4.5, Converse API + tool use | 구조화 출력이 없어 도구 스키마로 받고 자체 검증 |
| 임베딩 | `intfloat/multilingual-e5-small` ONNX (Python·FastAPI) | 다국어, CPU에서 빠름, PyTorch 없이 이미지 경량화 |
| 웹 | React 19, TypeScript, Vite, Web Worker | 큰 파일을 화면 멈춤 없이 브라우저에서 처리 |
| 음악 지도 | UMAP(Python, 한 번 돌리는 배치) + SVG 직접 그리기 | 지도 라이브러리 없이 포인터 이벤트로 확대·이동, 행성 400개에서 다시 그리기 약 40ms |
| 메일 | SES (`MAIL_MODE=log`면 로그로 대신) | 로컬·테스트는 비용 없이, 운영만 실제 발송 |
| 배포 | Docker, Helm, k3d(로컬) → k3s 단일 서버(운영, Terraform·GitHub Actions) → EKS 예정 | 같은 이미지·차트로 로컬 검증 후 운영, 태그를 올리면 검사·이미지·배포까지 자동 |

## 저장소 구조

```
backend/   core · api · worker · external-fetcher (Kotlin)
embed/     임베딩 서비스 (Python, ONNX)
web/       React 화면
deploy/    Dockerfile, Helm 차트
local/     로컬 PostgreSQL·LocalStack (compose)
scripts/   dev-up/down/status, k3d-up/down, test-all
tools/     가상 Takeout 생성기, 정확도 측정 도구
docs/      설계·결정·측정 기록
```

## 로컬 실행

필요: Docker, JDK 21, Node 22+, [uv](https://docs.astral.sh/uv/)

```bash
cp .env.example .env              # Last.fm·YouTube API 키를 넣는다 (없으면 해당 기능만 동작 안 함)
scripts/dev-up.sh                 # DB·LocalStack + 서비스 전체, 웹 http://localhost:15173
scripts/test-all.sh               # 백엔드·웹·embed 테스트
scripts/k3d-up.sh                 # 로컬 쿠버네티스 배포, http://localhost:18000
```

- 로컬 관리자 화면(`/admin`)은 가짜 계정 `local/admin-dev-seed.sql`을 넣어 씁니다. 운영 계정은 `tools/admin-account`로 따로 만듭니다.
- 기본은 LLM 가짜 응답(`LLM_MODE=fake`)이라 비용이 들지 않습니다. `LLM_MODE=bedrock`은 AWS 자격 증명이 필요하고 비용이 듭니다.
- 실제 Takeout 없이 시험하려면 `node tools/fake-takeout/generate.mjs`로 가상 파일을 만듭니다 (실제 파일 통계와 2% 안으로 일치).

## 문서

| 문서 | 내용 |
|---|---|
| [docs/design-v3.md](docs/design-v3.md) | 전체 설계: 목표·흐름·데이터 모델·AI·인프라·회차 일정·비용·위험 |
| [docs/l1-design.md](docs/l1-design.md) | L1 업로드·통계·k3d |
| [docs/l2-design.md](docs/l2-design.md) | L2 곡 식별·표지·검색·LLM 제목 정리 |
| [docs/l3-design.md](docs/l3-design.md) | L3 RAG 지식·곡 설명·임베딩·추천·평가와 측정 |
| [docs/todo/room-plan.md](docs/todo/room-plan.md) | 음악 방 기획·결정·음악 지도 측정 |
| [docs/todo/room-api-db.md](docs/todo/room-api-db.md) | 음악 방 API·DB 설계와 단계별 구현 기록 |
| [docs/todo/room-web.md](docs/todo/room-web.md) | 음악 방 웹 화면 범위 |
| [docs/todo/parallel.md](docs/todo/parallel.md) · [issues.md](docs/todo/issues.md) | 여러 AI 코딩 세션 병렬 작업 관리와 이슈 기록 |
| [docs/todo/deploy-decisions.md](docs/todo/deploy-decisions.md) | 배포 결정과 단계별 결과 |
| [docs/todo/single-server.md](docs/todo/single-server.md) · [single-server-terraform.md](docs/todo/single-server-terraform.md) · [cicd.md](docs/todo/cicd.md) | 단일 서버 운영 배포 설계·Terraform·CI/CD |
| [docs/todo/d4-map-data.md](docs/todo/d4-map-data.md) | 운영 서버로 지도·곡 데이터와 이미지 옮기기 |
| [docs/dev/local-tunnel.md](docs/dev/local-tunnel.md) | 로컬 서비스를 잠깐 외부에 여는 방법(Cloudflare Quick Tunnel)과 사용 기록 |
| [docs/legal/terms.md](docs/legal/terms.md) · [privacy.md](docs/legal/privacy.md) | 이용약관·개인정보 처리방침 원문 |
| [docs/testing.md](docs/testing.md) | 자동 테스트와 수동 점검표 |
| [CLAUDE.md](CLAUDE.md) | AI 코딩 도구(Claude Code) 작업 규칙 |

## 외부 데이터

MusicBrainz(CC0), Cover Art Archive, Last.fm API(비상업, 출처 표시), Wikipedia(CC BY-SA), YouTube Data API(음악 여부 판정에만)를 각 약관에 맞게 씁니다. 외부 호출은 `external-fetcher` 한 곳에서 API별 속도 제한(MusicBrainz 초당 1회)을 지킵니다. 가사와 오디오는 다루지 않습니다.

## 라이선스

[MIT](LICENSE)
