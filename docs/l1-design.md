# L1 설계: 로컬 백엔드 골격과 두 입구

- 작성일: 2026-10-20
- 상태: 승인 대기 (10장 "정할 것" 결정 필요)
- 상위 문서: `docs/design-v3.md` (내용은 v4) 12장 일정의 L1

L1의 목표는 **로컬에서 두 입구가 처음부터 끝까지 동작하는 것**이다. 곡 식별, LLM, 추천은 L2·L3에서 하고, L1은 그 앞까지만 만든다. AWS 비용은 0이다.

---

## 1. 범위

| 포함 (L1) | 제외 (다음 단계) |
|---|---|
| 백엔드 골격 (모듈, DB, 큐, 설정) | YouTube API로 음악인지 판정 → L2 |
| A 입구: 곡 검색, 3~5곡 선택·저장 | 곡 식별, LLM 제목 정리 → L2 |
| B 입구: 브라우저 필터 → 업로드 → 큐 → 저장 | RAG, 임베딩, 추천 → L3 |
| 기본 통계 (제목·채널 단위) | KEDA 확장 측정, AWS 배포 → EKS 회차 |
| 세션 토큰 링크, 즉시 삭제 | 만료 데이터 자동 삭제 작업 → L2 |
| 가상 Takeout 생성기 (실제 파일이 오기 전 개발용) | |

## 2. 흐름

**A. 곡 고르기**

```
검색어 입력 (0.5초 멈추면 검색)
 → api: 우리 DB 먼저 검색
 → 없으면 external-fetcher → MusicBrainz (초당 1회 제한)
 → 3~5곡 선택 → seed_track 저장 → 결과 링크 /r/:token
```

**B. 청취 기록 업로드**

```
브라우저 (Web Worker)
 1. watch-history.json 읽기
 2. 규칙 필터: 유튜브 뮤직 앱 기록, " - Topic" 채널, 삭제된 영상 제외
 3. 미리보기: 전체 N건 / 음악 M건 / 미판정 K건   ← 미판정은 L1에선 버림
 4. 음악 항목만 JSON으로 묶음 → 서명 URL로 S3에 직접 업로드
                                    │ S3 업로드 이벤트
                                    ▼
 SQS:parse → parse-worker → listen_event 저장 → 세션 상태 "완료"
                                    ▼
 결과 화면 /r/:token → 기본 통계
```

- 일반 영상 제목은 서버로 보내지 않는다. L1에서는 미판정 항목도 보내지 않는다.

## 3. 모듈

| 모듈 | L1에서 하는 일 |
|---|---|
| `backend/core` | 도메인 모델, 공통 설정, DB 접근 |
| `backend/api` | REST API, 서명 URL 발급, 통계 조회 |
| `backend/worker` | parse 역할만 (역할은 환경 변수로 지정) |
| `backend/external-fetcher` | MusicBrainz 검색만, 초당 1회 제한, 내부 HTTP로 api에 제공 |
| `web/` | 입구 선택, 곡 고르기, 업로드(Web Worker 필터), 결과·통계 화면 |
| `tools/fake-takeout` | 가상 청취 기록 생성기 (예: 5만 건, 음악·일반 영상 섞음) |

- `mcp-server`는 L4에서 만든다.

## 4. API

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| POST | `/api/sessions` | 세션 생성 → 무작위 토큰 |
| GET | `/api/tracks/search?q=` | 곡 검색 (DB → MusicBrainz) |
| POST | `/api/sessions/{token}/seeds` | 고른 곡 3~5개 저장 |
| POST | `/api/sessions/{token}/upload-url` | S3 서명 URL 발급 (JSON만, 크기 상한) |
| GET | `/api/sessions/{token}` | 상태와 단계별 건수 (화면이 몇 초마다 조회) |
| GET | `/api/sessions/{token}/stats` | 기본 통계 |
| DELETE | `/api/sessions/{token}` | 즉시 삭제 (DB + S3) |

## 5. 데이터 (L1에서 만드는 테이블)

| 테이블 | 내용 |
|---|---|
| `session` | 토큰, 입구(A/B), 상태, 단계별 건수, 만료 시각(30일) |
| `listen_event` | 영상 ID, 제목, 채널, 재생 시각, 출처(뮤직 앱/일반 앱), 만료 시각(7일). 곡 ID 칸은 비워 두고 L2에서 채운다 |
| `seed_track` | 고른 곡의 MusicBrainz ID, 제목, 아티스트 |

- DB 변경은 Flyway로 관리한다. pgvector 확장도 첫 마이그레이션에서 켜 둔다(실제로 쓰는 건 L3).
- 같은 업로드 메시지가 두 번 와도 중복 저장되지 않게 업로드 단위로 고유 제약을 건다.

## 6. 기본 통계 (곡 식별 전이라 "제목 + 채널" 단위)

- 기간, 총 재생 수
- 많이 들은 제목 20개, 많이 들은 채널(아티스트 추정) 20개
- 시간대별(새벽·출근·낮·저녁)·요일별 분포, 월별 추이 (한국 시간 기준)
- 유튜브 뮤직 앱과 일반 유튜브 앱 비율

## 7. 로컬 환경

| 용도 | 구성 |
|---|---|
| 평소 개발 (빠름) | docker compose로 PostgreSQL + pgvector와 LocalStack(S3·SQS) → 백엔드는 IDE에서 실행, 웹은 Vite 개발 서버 |
| L1 마무리 확인 | k3d에 `deploy/base` + `deploy/overlays/local`로 올려 같은 흐름을 한 번 확인 |

- LocalStack에서도 "S3 업로드 → SQS 메시지" 연결이 동작하므로 실제 AWS 없이 B 흐름 전체를 돌릴 수 있다.

## 8. 테스트

| 종류 | 대상 |
|---|---|
| 웹 단위 테스트 (Vitest) | 브라우저 필터 규칙 (뮤직 앱, Topic 채널, 삭제 영상) |
| 백엔드 단위 테스트 (JUnit) | 업로드 JSON 검증, 통계 계산 |
| 통합 테스트 (Testcontainers) | PostgreSQL + LocalStack을 띄워 업로드 → 큐 → 저장 → 통계, 삭제 |
| 전체 흐름 (수동) | 가상 Takeout 5만 건으로 브라우저에서 처음부터 끝까지 |

## 9. 완료 기준

1. 가상 Takeout 5만 건으로 B 흐름이 업로드부터 통계 화면까지 동작한다.
2. A 흐름에서 검색해 3~5곡을 고르면 저장되고 결과 링크가 열린다.
3. 삭제 버튼을 누르면 DB와 S3에서 실제로 사라진다.
4. 통합 테스트가 통과한다.
5. k3d에 올려 같은 흐름을 1회 확인한다.

## 10. 정할 것

| 항목 | 제안 | 결정 |
|---|---|---|
| 평소 개발 때 AWS 서비스 | LocalStack (무료, 오프라인). 실제 AWS 확인은 회차 ② 전에 한 번 | 미정 |
| Tilt 같은 자동 반영 도구 | L1에서는 쓰지 않음. compose + IDE 실행이 더 단순 | 미정 |
| MusicBrainz 연락처 | 요청 헤더에 앱 이름과 연락처가 필요. 이메일(예: turncoat19@gmail.com) 또는 GitHub 주소 | 미정 |
| 버전 | Kotlin·Spring Boot·JDK·Node는 시작할 때 최신 안정 버전을 확인해 고정 | 미확인 |
