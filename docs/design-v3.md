# 설계안 v3: 내 음악 결 (코드명 `gyeol`)

- 작성일: 2026-09-26
- 상태: 확정 (도메인 이름만 미정)
- 변경 이력
  - v1: 서비스·구조·AI·인프라·일정 초안
  - v2: Route 53 도메인, S3 + CloudFront 프론트, Terraform 두 스택 분리
  - v3: 목적(면접 이야기, 실제 사용자) 기준 재정렬 — 입구 두 개, 기간을 나눈 운영, 성공 기준 숫자

---

## 1. 개요

| 항목 | 내용 |
|---|---|
| 서비스 | 유튜브 청취 기록이나 좋아하는 곡 몇 개를 넣으면, 취향을 분석하고 **근거와 함께** 결이 맞는 곡을 추천 |
| 목적 | ① 면접에서 말할 EKS·AI 경험(실측 수치 포함) ② 실제 사용자 |
| 기간·예산 | 2026-09-27 ~ 10-04 (10-04 전체 destroy), AWS 크레딧 $150 안 (도메인은 카드) |
| 스택 | Kotlin·Spring Boot, React·TypeScript·Vite, EKS, RDS PostgreSQL + pgvector, SQS, S3, CloudFront, Route 53, Bedrock(Claude) |

**확정된 선택**

| 항목 | 선택 |
|---|---|
| 도메인 | Route 53 저가 확장자 (이름 미정, 대안 is-a.dev) |
| LLM | 대량 작업(제목 정리, 곡 설명)은 Haiku 4.5, 추천 이유와 대화는 Sonnet 5 |
| 임베딩 | 클러스터 안에서 CPU로 도는 다국어 소형 모델 |
| 노드 | x86 |
| 데이터 보관 | 청취 기록 원본 7일, 분석 결과 30일, 즉시 삭제 가능 |
| 운영 | 9/27~10/1 밤마다 destroy, 10/2~10/4 계속 켜 둠 |

## 2. 성공 기준 (10/4에 남길 숫자)

**면접용 실측 수치 5개**
1. 클러스터 apply·destroy 소요 시간
2. 업로드가 몰릴 때 큐 길이 → 파드 수 → 노드 수 확장 타임라인과 완료 시간
3. 외부 API 한도 병목: 외부 호출 워커를 늘려도 처리량이 그대로인 것, 캐시를 미리 채운 경우와 비교
4. 캐시 적중률에 따른 업로드당 LLM 비용
5. RAG를 켰을 때와 껐을 때의 곡 설명 정확도와 추천 좋아요 비율

**실사용 지표**
- 실사용자 수 (목표 5~10명)
- 업로드·곡 선택 건수
- 추천 좋아요 비율
- 다시 방문한 비율

## 3. 입구와 사용자 흐름

| | A. 곡 직접 고르기 (빠른 입구) | B. Takeout 업로드 (깊은 분석) |
|---|---|---|
| 걸리는 시간 | 1분 | 파일 준비에 몇 시간~며칠 |
| 입력 | 곡 3~5개 검색해서 선택 | `watch-history.json` |
| 결과 | 추천과 이유 | 통계(곡·아티스트·시간대·기간) + 취향 요약 + 추천 |

**A 흐름**
- 검색은 우리 DB의 곡을 먼저 찾고, 없으면 MusicBrainz를 찾는다. MusicBrainz는 초당 1회 제한이라 입력이 멈추고 0.5초 뒤에 검색한다.
- 곡을 고르면 추천 파이프라인으로 넘긴다.

**B 흐름**
1. 브라우저 1차 필터(Web Worker): 유튜브 뮤직 앱 기록, Topic 채널, 제목·채널 규칙으로 음악을 골라낸다.
2. 애매한 항목은 영상 ID만 서버로 보낸다. 서버는 YouTube API로 카테고리와 길이만 보고 음악인지 판정해 결과만 돌려준다.
3. 브라우저는 음악으로 판정된 항목만 보낸다. 일반 영상 제목은 서버에 오지 않는다.
4. 음악 항목을 JSON으로 묶어 서명 URL로 S3에 올린다.
5. 처리 상태(식별 → 설명 보강 → 분석)를 보여 주고, 끝나면 결과를 보여 준다.

**공통**
- 추천마다 좋아요·별로 버튼
- 대화로 파고들기 ("작년 겨울 vs 올여름", "새벽 취향으로 더")
- 계정 없이 무작위 토큰 링크로 접근
- 결과 공유 링크와 즉시 삭제 버튼

## 4. 전체 구조

```
사용자 브라우저 (React SPA)
 ├─ https://<도메인>        ─▶ CloudFront ─▶ S3 (웹 빌드, OAC)
 ├─ https://<도메인>/api/*  ─▶ CloudFront ─▶ origin-api.<도메인> (ALB, HTTPS)
 │                                            └▶ api (Spring Boot)
 └─ 청취 기록 JSON ─서명 URL─▶ S3 업로드 버킷 (1일 뒤 삭제)
                                   │ 업로드 이벤트
                                   ▼
 SQS:parse ─▶ parse-worker ─▶ SQS:identify ─▶ identify-worker (KEDA)
                                                  │ LLM 제목 정리 (여러 개씩 묶어 호출)
                                                  ▼
                              external-fetcher (복제본 1개 고정, API별 속도 제한)
                              MusicBrainz · Last.fm · ListenBrainz · Wikipedia · YouTube
                                                  ▼
 SQS:enrich ─▶ enrich-worker (KEDA): 지식 청크 수집, RAG로 곡 설명 생성
                                   ▼
 embed 서비스 (CPU 모델, HPA) ─▶ RDS PostgreSQL + pgvector
                                   ▼
 SQS:profile ─▶ profile-worker: 취향 프로필 계산, 추천 후보·점수·LLM 재정렬
 mcp-server (Spring AI MCP): api와 같은 도구를 외부 AI 앱에 공개
 CronJob: 인기곡 미리 받아 두기 · 지식 문서 갱신 · 만료 데이터 삭제
 Route 53: ExternalDNS가 origin-api / grafana / argocd 레코드를 자동 관리
```

**핵심 설계 결정**
- 외부 API 호출은 한 곳으로 모은다. 외부 호출기는 복제본 1개로 고정해 API별로 속도를 제한하고, 내부 처리 워커만 KEDA로 늘린다. 외부 호출은 전역 캐시로 줄인다.
- 곡 식별 결과는 사용자끼리 함께 쓴다(전역 캐시). 사용자가 늘수록 LLM 비용이 내려간다.
- LLM이 곡 정보를 지어내지 못하게 한다. 곡 정보는 도구·데이터 결과로만 쓰고, LLM은 판단과 설명만 한다. 근거가 없으면 "근거 부족"으로 표시한다.

## 5. 모듈과 저장소 구조

```
gyeol/
 ├─ infra/foundation   Terraform: 유지 스택
 ├─ infra/cluster      Terraform: 매일 부수는 스택
 ├─ deploy/            Helm·Kustomize, Argo CD 앱 정의
 ├─ backend/           Gradle 멀티 모듈
 │   ├─ core             도메인, 정규화 규칙
 │   ├─ api              REST, 서명 URL, 결과, 대화(SSE)
 │   ├─ worker           큐 워커 (역할은 환경 변수로)
 │   ├─ external-fetcher 외부 호출, 속도 제한, 재시도, 캐시
 │   └─ mcp-server       Spring AI MCP 서버
 ├─ web/               React + TypeScript + Vite
 ├─ tools/             가상 Takeout 생성기, k6 스크립트
 └─ docs/              설계, 실험 기록
```

- LLM 호출은 Anthropic Java SDK + Bedrock 백엔드(`BedrockMantleBackend`)로 한다.
- 새 Bedrock 연동은 structured outputs를 지원하지 않는다. JSON이 필요한 곳은 tool use 스키마로 받고 우리 쪽에서 검증한다.
- GitHub 저장소 생성과 push는 사용자가 직접 한다.

## 6. 데이터 모델과 보관

| 테이블 | 내용 | 보관 |
|---|---|---|
| `session` | 접근 토큰, 입구 종류(A/B), 상태, 단계별 건수 | 30일 |
| `listen_event` | 곡, 재생 시각, 출처(유튜브 뮤직 앱/일반 유튜브 앱) | 7일 |
| `seed_track` | A 입구에서 고른 곡 | 30일 |
| `title_alias` | 정규화한 "제목+채널" → 곡 (전역 캐시) | 계속 |
| `track` / `artist` | MusicBrainz ID, 앨범, 작곡·편곡 관계 | 계속 |
| `track_descriptor` | 분위기·장르·템포·보컬 스타일, 근거 단위(곡/앨범/아티스트), 출처 | 계속 |
| `knowledge_chunk` | 출처 URL, 라이선스, 본문, 벡터 | 계속 (출처 표시) |
| `track_vector` | 곡 설명 임베딩 | 계속 |
| `recommendation` / `feedback` | 추천, 이유, 좋아요·별로 | 30일 |
| `video_check` | 영상 ID → 음악 여부 판정 | 30일 (YouTube API 정책) |
| `agent_audit` | 에이전트 도구 호출 기록 | 30일 |

## 7. AI 설계

| 단계 | 방식 | 모델 |
|---|---|---|
| 제목 정리·곡 식별 | 제목 20~50개를 묶어 아티스트·곡명·버전(원곡/라이브/커버/모음)을 받고, MusicBrainz로 확정 | Haiku 4.5 |
| 곡 설명 보강 (RAG) | 곡 → 앨범 → 아티스트 순으로 지식 청크(위키백과 한·영·일, Last.fm 위키, MusicBrainz 관계)를 검색하고, 근거가 있는 항목만 채움 | Haiku 4.5 |
| 취향 프로필 | 재생 횟수에 로그를 씌워 가중(믹스에서 한 번 흘려들은 곡은 약하게), 시간대별(새벽·출근·주말) 프로필 | 없음 |
| 추천 | 후보(Last.fm·ListenBrainz 비슷한 곡·아티스트, 벡터 이웃) → 이미 들은 곡·모음 영상 제외 → 점수(청취 데이터 유사도 + 취향 벡터 + 같은 작곡가 가산) → 상위 50곡을 LLM이 분위기로 재정렬해 20곡과 근거 | Sonnet 5 |
| 대화 에이전트 | 도구: 기간별 통계, 비슷한 곡 찾기, 곡 설명, 기간 비교, 지식 검색. 전부 읽기 전용이고 호출마다 감사 로그 | Sonnet 5 |
| MCP 서버 | 위 도구를 그대로 공개 | 없음 |
| 모델 비교 | 작은 평가 세트로 재정렬·이유를 Haiku와 Sonnet으로 비교해 선택 근거를 남김 | 둘 다 |

- 부하 테스트에서는 LLM을 가짜 응답으로 바꾼다(설정 하나로 전환).
- Bedrock은 서울 리전에서 Global 엔드포인트라 할증이 없고, 인용(citations) 기능을 지원한다.

## 8. 외부 데이터와 약관 준수

| 출처 | 쓰는 방식 | 지킬 것 |
|---|---|---|
| YouTube Data API | `videos.list`로 음악 여부만 판정 (50개당 1유닛, 하루 1만 유닛) | 오디오 내려받기 금지, 파생 데이터 만들지 않기, 30일마다 갱신 또는 삭제, 카테고리 값은 저장 안 함 |
| Google Takeout | 사용자 본인 데이터 | 브라우저에서 필터, 원본 7일 뒤 삭제 |
| Last.fm | 비슷한 곡·아티스트, 태그, 위키 | 비상업, 출처 표시와 링크, 초당 1회 정도 |
| MusicBrainz | 곡 식별, 관계 정보 (CC0) | 앱을 알리는 User-Agent 헤더, 초당 1회 |
| ListenBrainz | 비슷한 곡·아티스트 | 호출 간격 지키기 |
| Wikipedia | 지식 청크 (CC BY-SA) | 출처 표시, 인용은 짧게 |
| 가사, 음악 웹진 리뷰 | 쓰지 않음 | 저작권 |

## 9. 인프라

**Terraform 두 스택**

| 스택 | 자원 | 운영 |
|---|---|---|
| `foundation` (유지) | Route 53 호스팅 영역(가져오기), ACM 인증서 2개(CloudFront용은 us-east-1, ALB용은 서울), CloudFront, S3(웹·업로드), ECR, VPC(가용 영역 2개, 퍼블릭·프라이빗 서브넷, S3 게이트웨이 엔드포인트), RDS PostgreSQL + pgvector, SQS 4개와 DLQ, Secrets Manager(API 키), ALB 보안 그룹, AWS Budgets 알림 | 한 번 만들고 유지 |
| `cluster` (매일 부숨) | NAT 게이트웨이 1개, EKS, 시스템 노드 그룹(t3.medium × 2), Karpenter(스팟 워커), Pod Identity 연결, 애드온 | 개발 기간엔 밤마다 destroy |

- 클러스터 애드온: AWS Load Balancer Controller, ExternalDNS, KEDA, metrics-server, EBS CSI, kube-prometheus-stack, Loki(S3 저장), Argo CD
- 권한: 파드마다 Pod Identity로 최소 권한(S3, SQS, Bedrock, Secrets Manager). 액세스 키는 쓰지 않는다.
- Terraform 상태를 저장할 S3 버킷은 처음에 한 번 따로 만든다.

**CloudFront**

| 경로 | 원본 | 설정 |
|---|---|---|
| `/*` | S3 웹 버킷 (OAC) | 캐시 사용. `/r/:token` 같은 SPA 경로는 `index.html`로 돌려줌 |
| `/api/*` | `origin-api.<도메인>` (ALB, HTTPS) | 캐시 끔, 모든 메서드, 응답 대기와 keep-alive 60초, SSE는 15초 하트비트 |

- ALB는 CloudFront 원본용 IP 목록과 비밀 헤더로만 받는다.
- 클러스터가 없는 밤에는 웹 화면에 "서버 쉬는 시간" 안내를 띄운다.

## 10. 관측과 배포

- 지표: 큐 길이, KEDA 복제본 수, Karpenter 노드 수, 외부 API 호출률·오류, 전역 캐시 적중률, Bedrock 토큰·추정 비용, 처리 시간, 추천 응답 p95, 좋아요 비율, 실사용자 수
- 로그와 알림: Loki로 로그를 모으고, DLQ 적재와 외부 API 오류율에 알림. 대시보드는 `grafana.<도메인>`
- 배포
  - 백엔드: GitHub Actions(테스트 → 이미지 → ECR, OIDC 인증) → Argo CD
  - 웹: GitHub Actions(빌드 → S3 → CloudFront 캐시 무효화)

## 11. 실험 계획

| 실험 | 방법 |
|---|---|
| 업로드 폭증 | 가상 Takeout(5만 건) 여러 개를 동시에 올리고, 큐·파드·노드 변화와 완료 시간을 기록 |
| 외부 한도 병목 | 외부 호출 워커 수를 바꿔 처리량을 비교하고, 캐시를 미리 채운 경우와 대조 |
| 캐시 효과 | 곡이 겹치는 가상 사용자를 늘려 가며 업로드당 LLM 비용을 기록 |
| RAG 품질 | RAG를 켜고 끄고 곡 설명 정확도(위키 원문과 대조)와 좋아요 비율을 비교 |
| IaC | `cluster` 스택 apply·destroy 시간을 매일 기록 |

## 12. 일정 (어느 단계에서 멈춰도 동작)

| 날짜 | 작업 | 끝나면 |
|---|---|---|
| 9/27 (일) | `foundation` 스택(도메인 등록이 늦으면 CloudFront 기본 주소로 먼저), `cluster` 스택과 애드온, apply·destroy 시간 측정 | 인프라 (주제 무관) |
| 9/28 | 백엔드 골격, A 입구(곡 고르기), B 입구(브라우저 필터, 업로드), 기본 통계 | 1단계 |
| 9/29 | 식별 파이프라인(큐, 워커, 외부 호출기, 전역 캐시, LLM 묶음 호출) | 곡 단위 분석 |
| 9/30 | RAG 지식 인입, 임베딩 서비스, 곡 설명 보강 | 2단계 |
| 10/1 | 추천과 이유, 피드백, 관측 대시보드 | 3단계, 실사용 준비 완료 |
| 10/2 | 실사용 시작(계속 켜 둠), 친구 초대, GitHub Actions와 Argo CD | 실사용 |
| 10/3 | 대화 에이전트, MCP 서버, 친구 궁합, 실험 1~4 | 4단계, 실측 수치 |
| 10/4 | 수치와 기록 정리, 전체 destroy | 마감 |

- 밀리면 줄이는 순서: MCP 서버 → 친구 궁합 → 대화 에이전트 → Argo CD → Loki
- 지키는 선: 3단계, 실험 1·2, 좋아요 수집

## 13. 비용 (추정, 서울 단가 확인 필요)

| 항목 | 금액 |
|---|---|
| `foundation` 8일 (RDS, 호스팅 영역, 기타) | 약 $7 |
| `cluster` (개발 5일 × 10시간 + 실사용 3일 × 24시간) | 약 $35~45 |
| LLM (Bedrock) | 약 $10~30 |
| 크레딧 사용 합계 | 약 $55~85 / $150 |
| 도메인 (카드, 크레딧 불가) | 연 $3~15 안팎 |

- AWS Budgets 알림: $100, $140

## 14. 위험과 첫날 확인할 것

| 항목 | 확인 또는 대응 |
|---|---|
| 무료 플랜에서 Bedrock Claude 호출 | 플레이그라운드에서 Haiku 4.5 호출. 안 되면 LLM 단계를 가짜 응답으로 두고 결정 |
| 무료 플랜에서 도메인 등록 | 막히면 Paid 전환 여부 결정 (크레딧 유지, 초과분은 카드) |
| CloudFront를 거친 SSE | 대기 시간이 데이터 조각 사이에도 적용되는지 확인 |
| 임베딩 모델·서빙 컨테이너 | x86 CPU에서 다국어 모델이 도는지 |
| Spring AI MCP 서버 | 쓰려는 Spring Boot·Kotlin 버전과 호환되는지 |
| 국내 곡 데이터가 얇음 | 앨범·아티스트 단위로 내려가고, 근거 단위와 신뢰도를 화면에 표시 |
| Takeout 대기 | 가상 기록으로 먼저 개발하고, 친구들에게는 미리 요청 |
| 일정 부족 | 12장의 줄이는 순서를 따름 |
| 개인정보 | 브라우저 필터, 7일·30일 삭제, 계정 없음, 즉시 삭제 버튼 |

## 15. 사전 준비 (계정·결제 작업, 사용자가 직접)

1. Route 53 도메인 등록 (카드 결제, 자동 갱신은 필요에 따라 끄기)
2. AWS Budgets 알림 ($100, $140)
3. Google Takeout 요청 (본인 + 써 줄 친구들, "YouTube 및 YouTube Music → 기록", JSON)
4. Last.fm API 키
5. YouTube Data API 키 (Google Cloud 프로젝트)
6. Bedrock 플레이그라운드에서 Haiku 4.5 호출 확인
7. GitHub 저장소 생성
8. 로컬 AWS 자격 증명 (Terraform용)

## 16. 근거 자료

- YouTube Data API 할당량: https://developers.google.com/youtube/v3/determine_quota_cost
- YouTube API Services 개발자 정책: https://developers.google.com/youtube/terms/developer-policies
- watch-history.json 형식: https://playbackstats.com/guides/youtube-watch-history-json
- Spotify Web API 변경 (2024-11-27): https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api
- Deezer 개발자 이용약관: https://developers.deezer.com/termsofuse
- Last.fm track.getSimilar: https://www.last.fm/api/show/track.getSimilar
- ListenBrainz Labs similar-recordings: https://labs.api.listenbrainz.org/similar-recordings
- Claude in Amazon Bedrock: https://platform.claude.com/docs/en/build-with-claude/claude-in-amazon-bedrock
- CloudFront 원본 설정: https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/DownloadDistValuesOrigin.html
- Route 53 가격: https://aws.amazon.com/route53/pricing/
