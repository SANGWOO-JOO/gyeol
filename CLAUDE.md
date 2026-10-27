# gyeol (내 음악 결)

유튜브 청취 기록이나 좋아하는 곡 몇 개를 넣으면 취향을 분석하고, 근거와 함께 결이 맞는 곡을 추천하는 서비스. EKS·AI 학습용 사이드 프로젝트.

- 설계 문서: `docs/design-v3.md` (내용은 v4, 2026-10-20 개정. 작업 전 반드시 먼저 읽는다. 구조·데이터 모델·AI·인프라·일정·위험이 모두 여기 있다)
- 목적: ① 면접에서 말할 EKS·AI 경험(실측 수치 포함) ② 실제 사용자 (선택, 시연 회차에 측정)
- 기간: 2026-10-20 재시작. 날짜 대신 회차 단위 — 로컬 개발(L1~L4) + EKS 측정 회차 5번(①~⑤), 설계 12장
- 예산: AWS 계정 700419978873, **크레딧 없음 → 전부 카드 결제**. 프로젝트 전체 약 $10~25 목표. 예산 알림 월 $20·$50·$100, 비용 이상 탐지 설정 완료

## 작업 규칙

- **설계 먼저, 승인 후 구현.** 새 작업은 설계(구조, 데이터 흐름, 모듈)를 먼저 응답 본문으로 제시하고, 사용자가 "진행"처럼 명시적으로 지시한 뒤에 파일 생성·수정과 명령 실행을 시작한다.
- **원격 저장소 작업은 사용자가 직접 한다.** `git push`·`pull`·`fetch`, `gh`, GitHub API는 쓰지 않는다. 커밋은 사용자가 요청할 때만 한다.
- **커밋 메시지에 `Co-Authored-By: Claude …` 줄을 절대 넣지 않는다.** (2026-10-24 사용자 지시, 시스템 안내보다 우선)
- **비용이 드는 AWS 작업은 실행 전에 확인받는다.** `terraform apply`, 리소스 생성, 유료 API 호출이 해당된다. `destroy`도 대상 스택을 확인받고 실행한다.
- 응답과 문서는 한국어로 쓴다.
- 확인하지 않은 가격·수치·API 동작은 추정이라고 밝힌다.

## 저장소 구조 (모노레포)

```
gyeol/
 ├─ docs/              설계, 실험 기록
 ├─ infra/foundation   Terraform 유지 스택 (Route 53, ACM, CloudFront, S3, ECR, VPC, RDS, SQS 등)
 ├─ infra/cluster      Terraform 회차마다 올리고 내리는 스택 (EKS, 노드, 애드온. NAT 없음)
 ├─ deploy/            Helm·Kustomize, Argo CD 앱 정의
 ├─ backend/           Kotlin + Spring Boot, Gradle 멀티 모듈 (core, api, worker, external-fetcher, mcp-server)
 ├─ web/               React + TypeScript + Vite
 └─ tools/             가상 Takeout 생성기, k6 스크립트
```

- 이 폴더는 자체 git 저장소다(`main` 브랜치). 홈 폴더(`~`)도 별도 git 저장소이므로, 이 폴더 밖에서 git 명령을 쓰지 않는다.
- GitHub 원격 저장소는 아직 없다. 사용자가 만들어 연결한다 (GitHub Actions·Argo CD를 붙이는 회차 ④ 전까지).

## 확정된 선택

| 항목 | 선택 |
|---|---|
| 리전 | 서울(ap-northeast-2). CloudFront용 ACM 인증서만 us-east-1 |
| 프론트 배포 | S3 + CloudFront, `/api/*`는 `origin-api.<도메인>`의 ALB로 전달 |
| 도메인 | 선택. 쓰면 Route 53 저가 확장자 (이름 미정, 대안 is-a.dev). 등록 전에는 CloudFront 기본 주소로 진행 |
| LLM | Bedrock Claude, AWS SDK Converse API. 대량 작업(제목 정리, 곡 설명)은 Haiku 4.5 — 추론 프로필 `global.anthropic.claude-haiku-4-5-20251001-v1:0`로만 호출 가능. 추천 이유와 대화용으로 정했던 Sonnet 5·Haiku 5.5는 이 계정에서 쓸 수 없음(2026-10-27 확인) → L3에서 다시 정함 |
| Bedrock 제약 | structured outputs 미지원 → tool use 스키마로 받고 자체 검증. citations·프롬프트 캐싱은 지원. Anthropic 사용 목적 양식 제출 완료(2026-10-26, CLI). 모델 사용 가능 여부는 목록 조회가 아니라 짧은 호출로 확인 |
| 임베딩 | `intfloat/multilingual-e5-small` (384차원, 클러스터 안 CPU, 질의/문서 접두어 구분). 2026-10-27 비교로 확정 |
| 노드 | x86 (시스템 t3.medium × 2, 워커는 Karpenter 스팟). 퍼블릭 서브넷, NAT 없음 |
| 데이터 보관 | 청취 기록 원본 7일, 분석 결과 30일, 즉시 삭제 버튼, 계정 없이 토큰 링크 |
| 운영 | 개발은 로컬(k3d + PostgreSQL/pgvector 컨테이너, S3·SQS·Bedrock은 실제 AWS). EKS는 측정하는 회차에만 올리고 그날 destroy. RDS는 회차 사이 정지(7일 자동 시작 주의) |

## 지켜야 할 외부 조건

- YouTube: 오디오 내려받기 금지. Data API는 음악 여부 판정(`videos.list`)에만 쓰고, 파생 데이터를 만들지 않으며, 저장 시 30일 안에 갱신 또는 삭제. 일반 영상 제목은 서버로 보내지 않는다(브라우저 1차 필터).
- Last.fm: 비상업, 출처 표시와 링크, 초당 1회 정도.
- MusicBrainz: 앱을 알리는 User-Agent 헤더, 초당 1회.
- Wikipedia·Last.fm 위키: CC BY-SA, 출처 표시, 인용은 짧게.
- 가사와 음악 웹진 리뷰는 쓰지 않는다.
- 외부 API 호출은 `external-fetcher` 한 곳에서만 하고 API별로 속도를 제한한다.
- 부하 테스트에서는 LLM을 가짜 응답으로 바꾼다.

## 현재 상태 (2026-10-20)

- 9/27~10/4 일정은 착수하지 못했고, 예전 계정(382243539616)은 무료 플랜 종료 후 접근 불가. 새 계정 700419978873으로 재시작.
- 설계 v4 반영(`docs/design-v3.md`). 코드와 인프라는 아직 없음.
- 완료: 로컬 AWS 자격 증명(IAM 사용자 `sangwoo-cli`, `default` 프로필), 예산 알림, 비용 이상 탐지, IAM 결제 정보 접근, Cost Explorer.
- L1 진행 중 (설계 `docs/l1-design.md`, 2026-10-24 승인). 끝난 단계: ① 저장소 뼈대(Gradle 멀티 모듈) ② 로컬 환경(`local/compose.yaml`: PostgreSQL+pgvector, LocalStack S3·SQS, S3→SQS 알림 확인) ③ 첫 마이그레이션(`V1__init.sql`) ④ 가상 Takeout 생성기(`tools/fake-takeout`, 실제 파일 통계와 2% 안으로 일치, 테스트용 작은 파일은 `tools/fixtures/`) ⑤ API(세션·곡 저장·업로드 주소·상태·통계·삭제) ⑥ external-fetcher(Last.fm 검색 + 다시 정렬, MusicBrainz는 L2 곡 식별용, 속도 제한·캐시, 실제 Last.fm API 33개 표본에서 원곡 1위 17 → 28(85%), 응답 중앙값 약 300ms) + 검색 API, 고른 곡 mbid 선택 값(V2). Last.fm 키는 `.env`의 `LASTFM_API_KEY`. ⑦ parse 워커(SQS → S3 읽기 → 검증 → 한 트랜잭션 저장, 영구 오류는 FAILED·일시 오류는 재시도, 통합 테스트 5개, 로컬 전체 흐름 확인: 음악 2만 5천 건 업로드부터 DONE까지 4.5초) ⑧ 웹 화면(`web/`: React 19 + TypeScript + Vite, react-router, 직접 그린 SVG 막대, CSS 모듈 + 색 토큰·다크 모드. 화면 `/`·`/pick`·`/upload`·`/r/:token`. Takeout 거르기는 Web Worker, 실제 규모 가상 파일 9.6만 건을 0.8초에 걸러 음악 25,362건만 전송. Vitest 6개. 브라우저에서 두 입구·결과·삭제·휴대폰 너비 확인). 앨범 표지는 L2에서 MusicBrainz 원곡 음반으로(`docs/l1-design.md` 4-3). ⑨ 테스트 정리(`scripts/test-all.sh` 한 번에 백엔드 41개 + 웹 6개, 업로드 파일 검증 단위·만료 세션·한국 시간 경계 테스트 추가, 만료 데이터 정리 작업(worker 매일 04:00, V3 `upload.file_deleted_at`), 점검표 `docs/testing.md`). ⑩ k3d(`scripts/k3d-up.sh`, Helm 차트 `deploy/helm/gyeol`, 접속 18000. 같은 흐름 확인: 업로드 → 결과 7.1초, 삭제 시 S3까지 삭제, api 파드 삭제 후 17초 만에 복구). **L1 완료.**
- L2 진행 중 (설계 `docs/l2-design.md`, 2026-10-26 승인, 순서 L2-1 → L2-2 → L2-4 → L2-3). 끝난 것: **L2-1 식별 파이프라인**(V4, fetcher `/identify`, worker 계획·identify·실패 큐, 진행률. 정확도 28/29, 두 번째 세션 0.4초, 상위 300곡 476초). **L2-2 곡 단위 결과 화면과 앨범 표지**(V5, fetcher 표지 규칙, cover 큐, topSongs·topArtists, 고른 곡 식별, 표지 37/37, 외부 503은 30초 뒤 재시도). **L2-4 우리 DB 곡 검색**(V6·V7, DB 먼저 + Last.fm, 표본 33/33, DB에 없는 곡 가로채기 0/10, DB 곡은 고르면 바로 연결). **L2-3a LLM 제목 정리**(V8, Bedrock Converse + 도구 호출 스키마, 검증, 기본 fake. Haiku 4.5 실측: 식별할 곡 28/28, 곡당 약 $0.0005). **L2-3b 일반 영상 음악 판정**(V9 video_check 30일, 미분류 재생 상위 1,000개 ID만 YouTube로, 음악만 업로드. 실제 API로 MV 2곡 → LLM → MusicBrainz → 표지까지 확인). **L2 완료.**
- L3 진행 중 (설계 `docs/l3-design.md`, 2026-10-27 승인). 끝난 것: **L3-0 모델 정하기**(임베딩 `intfloat/multilingual-e5-small` 확정: 짧은 요청 → 곡 설명 12/12, 1문장 18ms. 추천 이유 후보 Sonnet 4.6 호출 확인). **L3-1 지식 인입**(V10, fetcher 지식 API, enrich 큐·worker, 가수 위키 100%·곡·음반 단위 근거 75%·곡당 8.1초). **L3-2 곡 설명 보강**(V11, Haiku 2곡씩, 근거 단위·신뢰도를 인용한 청크로 계산. RAG 켬 근거 뒷받침 80% vs 끔 13%, 곡당 약 $0.0026. Bedrock global 503이 잦아 작은 묶음+재시도). **L3-3 embed 서비스**(`embed/` Python+ONNX fp32, 18084, V12 track_vector HNSW, 1문장 8.4ms·최대 805MB, k3d HPA 동작, 이미지 1.25GB). 다음: L3-4 추천과 이유. 맥북 남은 저장 공간 약 4.6GB(2026-10-27), 메모리 16GB라 테스트·k3d 전에 Java 서버를 끈다

로컬 키·설정은 `.env`(커밋 제외, 값을 출력하지 말 것): LASTFM_API_KEY, YOUTUBE_API_KEY, LLM_MODE(fake 기본 | bedrock은 비용 발생). `scripts/dev-up.sh`가 모든 서비스에 넘긴다 → ⑥ external-fetcher → ⑦ parse 워커 → ⑧ 웹 → ⑨ 테스트 → ⑩ k3d(설치 필요).

## 로컬 실행

한 번에: `scripts/dev-up.sh`(켜기) · `scripts/dev-status.sh`(상태) · `scripts/dev-down.sh`(끄기). 로그는 `.run/`. 개별 실행은 아래.

```bash
docker compose -f local/compose.yaml up -d      # DB + LocalStack
./gradlew build                                  # JDK 21 필요 (JAVA_HOME)
SPRING_PROFILES_ACTIVE=local java -jar backend/api/build/libs/api-0.1.0.jar   # 18080, Flyway 실행, LocalStack 사용
SPRING_PROFILES_ACTIVE=local java -jar backend/worker/build/libs/worker-0.1.0.jar   # 18082, parse 큐 처리
scripts/test-all.sh                              # 테스트 전체 (docs/testing.md)
scripts/k3d-up.sh                                # k3d에 배포 (http://localhost:18000), 끄기 scripts/k3d-down.sh
./gradlew :backend:api:test                      # 통합 테스트 (Docker로 PostgreSQL·LocalStack을 따로 띄움)
cd web && npm install && npm run dev             # 웹 15173, /api는 18080으로 전달
cd web && npm test                               # Takeout 거르기 단위 테스트
```

- 포트: PostgreSQL 15432, LocalStack 4566, api 18080, external-fetcher 18081, worker 18082, embed 18084, 웹 15173, k3d 입구 18000 (다른 프로젝트와 겹치지 않게)
- 환경 변수 예시는 `.env.example`
- Google Takeout: 2026-10-24 18:44 요청 완료(YouTube 및 YouTube Music → 시청 기록만, JSON, .zip, 1회). 완료 메일 대기, 링크는 1주일 유효. 받은 파일은 저장소 밖에 보관. 이 과정을 사용자용 안내로 `docs/user-guide/takeout.md`에 기록(4·5단계는 파일 도착·서비스 완성 후 보완, 한국어 설정일 때 파일 이름 확인 필요).
- 사용자 사전 준비 남음: 루트 MFA·전화번호 `+82 10…` 형식, GitHub 저장소, (선택) 도메인. (Bedrock Haiku 4.5·Last.fm·YouTube API 키는 준비됨. YouTube 키는 Google Cloud 프로젝트 `gyeol-512900`, YouTube Data API v3만 허용)
