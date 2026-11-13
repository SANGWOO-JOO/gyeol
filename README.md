# 결 (gyeol) — 친구와 함께 만드는 음악 지도, 근거가 붙은 취향 분석

> A shared music galaxy you build with friends, plus listening-history analysis and recommendations — with evidence for every claim.

**링크 하나로 친구들과 함께 만드는 음악 지도.** 각자 좋아하는 노래를 담으면, 비슷한 가수끼리 가까이 놓인 "음악 은하" 위에 행성과 위성으로 쌓입니다. 두 사람을 골라 취향이 얼마나 맞는지(코드 맞추기) 근거와 함께 보여 주고, 50곡마다 담은 노래로 캐릭터를 만들어 줍니다.
유튜브 청취 기록(Google Takeout)을 올리면 **많이 들은 곡·가수·시간대를 원곡 기준으로 분석**하고, 좋아하는 곡 몇 개를 고르면 위키백과·MusicBrainz·Last.fm **근거와 함께** 결이 맞는 곡 20곡을 추천합니다.
EKS·AI 경험을 실측 수치와 함께 쌓으려고 만든 사이드 프로젝트입니다.

| 진행 상황 | |
|---|---|
| 로컬 개발 L1~L3 (업로드·통계·곡 식별·RAG 보강·추천·평가) | 완료 |
| 실제 Takeout으로 청취 분석 품질 개선 (식별·표지·순위) | 완료 |
| 음악 방 (방·참가·담기, 음악 지도 데이터, 메일, 50곡 선물, 코드 맞추기, 관리자, 웹 화면·지도) | 로컬 완료 (메일은 로컬 가짜 발송, SES 도메인 미정) |
| 로컬 쿠버네티스(k3d) 배포 검증 | L3-3까지 완료, 이후 변경 확인 예정 |
| AWS EKS (Terraform, 측정하는 날만 올리고 내리는 회차 5번) | 진행 예정 |
| L4 (대화 에이전트, MCP 서버) | 보류 |

## 무엇을 하나

| 입구 | 입력 | 결과 |
|---|---|---|
| **음악 방** (첫 화면) | 방장이 이메일 인증으로 방을 만들고 초대 링크 공유, 최대 5명이 닉네임 + 4자리 암호로 참가해 노래를 검색해 담음 | 음악 은하 지도, 코드 맞추기, 50곡 선물 캐릭터 |
| 곡 고르기 | 곡 3~5개 검색해서 선택 | 추천 20곡과 이유 |
| 청취 기록 올리기 | Takeout `watch-history.json` | 많이 들은 곡·가수(원곡 기준, 페이지로 전체 보기)·시간대·기간 |

**음악 방**
- **음악 은하 지도:** 한·일·미 인기 가수 1,458명을 근거 기반 가수 설명 벡터 → UMAP 2D로 미리 배치한 공용 지도에서, 방에 담긴 가수만 행성으로 보여 줍니다. 담은 노래는 사람 색 위성, 여럿이 같이 담은 노래는 가장 안쪽 금빛 궤도. 지도에 없는 가수는 담는 순간 근거를 모아 이웃 평균 자리에 새로 올립니다.
- **코드 맞추기:** 두 사람의 장르 구역 비율·지도 거리·겹친 가수·겹친 노래로 점수를 SQL로 계산하고, LLM은 근거 안에서 이유만 씁니다. 곡이 적으면 점수가 낮게 치우치는 것을 측정해 5~9곡은 % 없이 겹친 것만 보여 줍니다.
- **50곡 선물:** 담은 노래가 50곡 단위에 처음 닿으면 상자 → 구역 비율로 LLM이 그림 묘사 → 이미지 모델이 캐릭터 생성(하루 예산 초과·실패 시 기본 캐릭터). 사람·방당 하루 1개.
- **보안:** 방마다 32바이트 열쇠(HttpOnly 쿠키 + 헤더), 4자리 암호 BCrypt·5회 실패 10분 잠금, 방장 암호는 이메일 코드로 재설정(다른 기기 열쇠 폐기), 관리자는 비밀번호 + OTP 2단계·감사 기록. 관리자도 방장 이메일 전체와 노래 목록은 보지 않습니다.

**공통**
- 계정 없이 무작위 토큰 링크로 접근, 즉시 삭제 버튼, 청취 기록 원본 7일·결과 30일 뒤 자동 삭제 (음악 방은 방장이 지울 때까지 보관, 숨긴 뒤 7일 뒤 완전 삭제)
- **원본 파일은 서버로 보내지 않습니다.** 브라우저(Web Worker)에서 음악 기록만 골라 올리고, 일반 영상 제목은 서버에 오지 않습니다.

## 구조

```mermaid
flowchart LR
  web[web<br/>React·Vite<br/>브라우저에서 거르기] -->|서명 URL| s3[(S3)]
  web --> api[api<br/>Spring Boot]
  s3 -->|업로드 알림| sqs[[SQS 큐 10개<br/>+ 실패 큐]]
  api -->|담기·선물·코드 맞추기| sqs
  api --> ses[SES<br/>방장 인증·재설정 메일]
  sqs --> worker[worker<br/>parse·identify·cover·guess·enrich·recommend<br/>room-song·gift-image·code-match·artist-image]
  api --> db[(PostgreSQL<br/>pgvector·pg_trgm)]
  worker --> db
  worker --> fetcher[external-fetcher<br/>외부 호출 한 곳, 속도 제한·캐시]
  worker --> embed[embed<br/>Python·ONNX<br/>multilingual-e5-small]
  worker --> bedrock[Bedrock<br/>Claude Haiku 4.5]
  fetcher --> ext[MusicBrainz · Cover Art Archive<br/>Last.fm · YouTube Data API · Wikipedia]
```

**처리 단계** (큐로 이어지고, 3번 실패하면 실패 큐로 보내 진행률이 멈추지 않게 실패로 표시)

1. **거르기** — 브라우저에서 시청 기록 9.6만 건 → 음악 2.5만 건 (0.8초). 애매한 영상은 ID만 보내 YouTube API로 음악 여부 판정
2. **저장** — S3 → 검증 → 한 트랜잭션 저장 (2.5만 건 4.5초) → 기본 통계
3. **곡 식별** — "[MV] 밤편지" 같은 제목을 MusicBrainz 원곡으로. 규칙 → LLM 제목 정리 → 다른 표기 재검색 → 전역 캐시
4. **표지** — 원곡 음반의 앞표지를 S3에 미리 저장. 원곡 음반이 없으면 같은 음반 묶음의 다른 판 (다른 음반 표지로 대신하지 않음)
   **방송 영상 가수 뽑기** — 많이 들은 방송·일반 채널 영상의 가수·곡을 LLM으로 읽어 확인된 가수일 때만 반영, 메들리는 곡마다
5. **보강 (RAG)** — 위키백과·Last.fm·MusicBrainz 근거 수집 → LLM이 근거 안에서만 곡 설명 → 벡터
6. **추천** — 취향 벡터 → 후보 50곡(Last.fm 두 경로 + 벡터 이웃) → 점수 → LLM 재정렬과 이유 → 검증
7. **평가** — 좋아요·별로, 이유가 틀렸는지 체크 (RAG 켬/끔 블라인드 비교)

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
| 배포 | Docker, Helm, k3d → EKS (Terraform 예정) | 같은 이미지·차트로 로컬 검증 후 EKS |

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
| [docs/testing.md](docs/testing.md) | 자동 테스트와 수동 점검표 |
| [CLAUDE.md](CLAUDE.md) | AI 코딩 도구(Claude Code) 작업 규칙 |

## 외부 데이터

MusicBrainz(CC0), Cover Art Archive, Last.fm API(비상업, 출처 표시), Wikipedia(CC BY-SA), YouTube Data API(음악 여부 판정에만)를 각 약관에 맞게 씁니다. 외부 호출은 `external-fetcher` 한 곳에서 API별 속도 제한(MusicBrainz 초당 1회)을 지킵니다. 가사와 오디오는 다루지 않습니다.

## 라이선스

[MIT](LICENSE)
