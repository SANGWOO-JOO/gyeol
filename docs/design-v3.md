# 설계안 v4: 내 음악 결 (코드명 `gyeol`)

- 작성일: 2026-09-26, 개정: 2026-10-20 (v4, 파일 이름은 그대로 둠)
- 상태: 확정 (도메인은 선택)
- 변경 이력
  - v1: 서비스·구조·AI·인프라·일정 초안
  - v2: Route 53 도메인, S3 + CloudFront 프론트, Terraform 두 스택 분리
  - v3: 목적(면접 이야기, 실제 사용자) 기준 재정렬 — 입구 두 개, 기간을 나눈 운영, 성공 기준 숫자
  - v4: 계정 교체(<계정 ID>, 크레딧 없음)에 맞춘 운영 변경 — 로컬 개발 + 측정하는 날만 EKS, NAT 제거, RDS 회차 사이 정지, 날짜 일정 → 회차 일정, 비용 재계산 (1·2·9·10·11·12·13·14·15장)

---

## 1. 개요

| 항목 | 내용 |
|---|---|
| 서비스 | 유튜브 청취 기록이나 좋아하는 곡 몇 개를 넣으면, 취향을 분석하고 **근거와 함께** 결이 맞는 곡을 추천 |
| 목적 | ① 면접에서 말할 EKS·AI 경험(실측 수치 포함) ② 실제 사용자 |
| 기간·예산 | 2026-10-20 재시작. 날짜 대신 회차 단위(로컬 개발 + EKS 측정 회차 5번). AWS 계정 <계정 ID>, **크레딧 없음 → 전부 카드 결제**, 프로젝트 전체 약 $10~25 목표 |
| 스택 | Kotlin·Spring Boot, React·TypeScript·Vite, EKS, RDS PostgreSQL + pgvector, SQS, S3, CloudFront, Route 53, Bedrock(Claude) |

**확정된 선택**

| 항목 | 선택 |
|---|---|
| 도메인 | 선택. 쓰면 Route 53 저가 확장자 (이름 미정, 대안 is-a.dev) |
| LLM | 대량 작업(제목 정리, 곡 설명)은 Haiku 4.5, 추천 이유와 대화는 Sonnet 5 |
| 임베딩 | 클러스터 안에서 CPU로 도는 다국어 소형 모델 |
| 노드 | x86 |
| 데이터 보관 | 청취 기록 원본 7일, 분석 결과 30일, 즉시 삭제 가능 |
| 운영 | 개발은 로컬(k3d + PostgreSQL/pgvector 컨테이너, S3·SQS·Bedrock은 실제 AWS). EKS는 측정하는 날에만 `cluster` 스택을 올리고 그날 내린다 |
| 네트워크 | NAT 없음. 노드는 퍼블릭 서브넷(인바운드 차단), S3는 게이트웨이 엔드포인트 |

## 2. 성공 기준 (회차마다 남길 숫자)

**면접용 실측 수치 5개**
1. 클러스터 apply·destroy 소요 시간
2. 업로드가 몰릴 때 큐 길이 → 파드 수 → 노드 수 확장 타임라인과 완료 시간
3. 외부 API 한도 병목: 외부 호출 워커를 늘려도 처리량이 그대로인 것, 캐시를 미리 채운 경우와 비교
4. 캐시 적중률에 따른 업로드당 LLM 비용
5. RAG를 켰을 때와 껐을 때의 곡 설명 정확도와 추천 좋아요 비율

**실사용 지표** (선택. 클러스터가 회차에만 떠 있으므로 시연 회차 ⑤에서 측정)
- 실사용자 수 (지인 몇 명이 미리 받은 Takeout으로 시연 시간에 사용)
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
 ├─ infra/cluster      Terraform: 회차마다 올리고 내리는 스택
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
| `foundation` (유지) | S3(웹·업로드), CloudFront, ECR, SQS 4개와 DLQ, Secrets Manager(API 키), VPC(가용 영역 2개, 퍼블릭·프라이빗 서브넷, S3 게이트웨이 엔드포인트), RDS PostgreSQL + pgvector, ALB 보안 그룹. 도메인을 쓰면 Route 53 호스팅 영역(가져오기)과 ACM 인증서 2개(CloudFront용은 us-east-1, ALB용은 서울) | 한 번 만들고 유지. **RDS는 회차 사이에 정지** |
| `cluster` (회차마다) | EKS, 시스템 노드 그룹(t3.medium × 2, 퍼블릭 서브넷), Karpenter(스팟 워커), Pod Identity 연결, 애드온 | 회차를 시작할 때 apply, 그날 destroy |

- **NAT 게이트웨이는 두지 않는다.** 노드는 퍼블릭 서브넷에 두고 보안 그룹 인바운드를 막는다. 외부 API와 Bedrock은 인터넷 게이트웨이로 바로 나가고, S3는 게이트웨이 엔드포인트로 간다. 운영 서비스라면 NAT가 맞지만, 카드로 결제하는 학습 계정에서 시간당 비용을 줄이려는 선택이다.
- **RDS는 정지해도 7일 뒤 AWS가 자동으로 다시 켠다.** 회차 간격이 7일을 넘으면 다시 정지시키는 점검이 필요하다. 정지 중에도 스토리지 요금은 나간다. 오래 쉴 때는 스냅샷을 찍고 지운다.
- 클러스터 애드온: AWS Load Balancer Controller, ExternalDNS, KEDA, metrics-server, EBS CSI, kube-prometheus-stack, Loki(S3 저장), Argo CD. Prometheus·Loki는 측정하는 회차(③④)에만 켠다.
- 권한: 파드마다 Pod Identity로 최소 권한(S3, SQS, Bedrock, Secrets Manager). 액세스 키는 쓰지 않는다.
- Terraform 상태를 저장할 S3 버킷은 처음에 한 번 따로 만든다.
- 계정 안전장치 (2026-10-20 설정 완료, Terraform 밖): 예산 `monthly-cost-guard`(실제 지출이 월 $20·$50·$100을 넘으면 메일), 비용 이상 탐지(이상 지출 영향 $5 이상이면 매일 메일), IAM 사용자 결제 정보 접근, Cost Explorer.

**회차 올리기·내리기 (`tools/` 스크립트)**

| 스크립트 | 순서 |
|---|---|
| up | RDS 시작 → `terraform apply`(cluster) → Argo CD가 애드온·앱 동기화 → 간단한 호출로 확인 |
| down | Ingress·LoadBalancer 서비스·PVC 삭제 → Karpenter 노드 정리 → `terraform destroy`(cluster) → RDS 정지 → 남은 자원 점검(ALB, EBS 볼륨, 공인 IP, EC2, EKS) |
| guard | 회차가 없는 날 실행: RDS가 자동으로 켜졌으면 정지, 클러스터 자원이 남았으면 알림 |

- 카드 결제라 남은 자원은 그대로 돈이 된다. down의 남은 자원 점검은 생략하지 않는다.

**CloudFront**

| 경로 | 원본 | 설정 |
|---|---|---|
| `/*` | S3 웹 버킷 (OAC) | 캐시 사용. `/r/:token` 같은 SPA 경로는 `index.html`로 돌려줌 |
| `/api/*` | `origin-api.<도메인>` (ALB, HTTPS) | 캐시 끔, 모든 메서드, 응답 대기와 keep-alive 60초, SSE는 15초 하트비트 |

- ALB는 CloudFront 원본용 IP 목록과 비밀 헤더로만 받는다.
- 클러스터가 없는 날에는 웹 화면에 "서버 쉬는 시간" 안내를 띄우고 업로드를 막는다. 이미 만들어진 결과 페이지는 계속 보이게 한다.

## 10. 관측과 배포

- 지표: 큐 길이, KEDA 복제본 수, Karpenter 노드 수, 외부 API 호출률·오류, 전역 캐시 적중률, Bedrock 토큰·추정 비용, 처리 시간, 추천 응답 p95, 좋아요 비율, 실사용자 수
- 로그와 알림: Loki로 로그를 모으고, DLQ 적재와 외부 API 오류율에 알림. 대시보드는 `grafana.<도메인>`
- 배포
  - 백엔드: GitHub Actions(테스트 → 이미지 → ECR, OIDC 인증)는 항상 돌고, Argo CD 동기화는 클러스터가 떠 있는 회차에만 일어난다
  - 웹: GitHub Actions(빌드 → S3 → CloudFront 캐시 무효화)
- 클러스터가 회차마다 새로 생기므로 Prometheus·Loki 데이터도 함께 사라진다. 회차가 끝나기 전에 그래프와 수치를 `docs/experiments/`에 기록한다.

## 11. 실험 계획

| 실험 | 방법 |
|---|---|
| 업로드 폭증 | 가상 Takeout(5만 건) 여러 개를 동시에 올리고, 큐·파드·노드 변화와 완료 시간을 기록 |
| 외부 한도 병목 | 외부 호출 워커 수를 바꿔 처리량을 비교하고, 캐시를 미리 채운 경우와 대조 |
| 캐시 효과 | 곡이 겹치는 가상 사용자를 늘려 가며 업로드당 LLM 비용을 기록 |
| RAG 품질 | RAG를 켜고 끄고 곡 설명 정확도(위키 원문과 대조)와 좋아요 비율을 비교 |
| IaC | `cluster` 스택 apply·destroy 시간을 회차마다 기록 |

## 12. 일정 (회차 단위, 어느 단계에서 멈춰도 동작)

**로컬 개발 (비용 거의 0)**

| 단계 | 작업 | 끝나면 |
|---|---|---|
| L1 | 백엔드 골격, A 입구(곡 고르기), B 입구(브라우저 필터, 업로드), 기본 통계 | 1단계 |
| L2 | 식별 파이프라인(큐, 워커, 외부 호출기, 전역 캐시, LLM 묶음 호출) | 곡 단위 분석 |
| L3 | RAG 지식 인입, 임베딩 서비스, 곡 설명 보강, 추천과 이유, 피드백 | 3단계 |
| L4 | 대화 에이전트, MCP 서버, 친구 궁합 | 4단계 (선택) |

- 로컬 환경: k3d(로컬 쿠버네티스)와 PostgreSQL + pgvector 컨테이너. S3·SQS·Bedrock은 실제 AWS를 쓴다(사용량 요금은 몇 센트~몇 달러).

**EKS 회차 (그날 올리고 그날 내림)**

| 회차 | 작업 | 시간 | 언제 |
|---|---|---|---|
| ① | `foundation` 스택, `cluster` 스택과 애드온, up·down 스크립트, apply·destroy 시간 측정 | 2~3시간 | 아무 때나 (주제 무관) |
| ② | 앱 배포, ALB·CloudFront 연결, Pod Identity, RDS 연결 | 4시간 | L1 이후 |
| ③ | 실험 1~3 (업로드 폭증, 외부 한도 병목, 캐시 효과), KEDA·Karpenter 스팟 | 4시간 | L3 이후 |
| ④ | Prometheus·Grafana·Loki, GitHub Actions·Argo CD | 4시간 | ③과 합쳐도 됨 |
| ⑤ | 시연(지인 사용), 실험 4(RAG 품질), 수치 정리, 전체 destroy | 2시간 | 마지막 |

- 밀리면 줄이는 순서: MCP 서버 → 친구 궁합 → 대화 에이전트 → Argo CD → Loki
- 지키는 선: 3단계, 회차 ①·③, 좋아요 수집

## 13. 비용 (추정, 서울 단가 확인 필요. 크레딧 없음, 전부 카드)

| 항목 | 금액 |
|---|---|
| EKS 회차 약 18시간 (컨트롤 플레인, 시스템 노드, ALB, 공인 IP. NAT 없음) | 약 $5 |
| 스팟 워커 (회차 ③) | 약 $1~2 |
| RDS 회차 중 가동 약 18시간 | 약 $0.5 |
| Bedrock (부하 실험은 가짜 응답) | 약 $3~10 |
| 로컬 개발 중 S3·SQS·Bedrock 사용량 | 약 $1~5 |
| **프로젝트 합계** | **약 $10~25** |
| 유지 비용 (정지 중 RDS 스토리지, Secrets Manager, S3 등) | 월 약 $3~5 |
| 도메인 (선택) | 연 $3~15, Route 53 호스팅 영역 월 $0.5 |

- 예산 알림: 월 $20·$50·$100 (설정 완료)
- 회차를 다 끝내고 오래 쉴 때는 RDS를 스냅샷 찍고 지워 유지 비용을 줄인다.

## 14. 위험과 첫날 확인할 것

| 항목 | 확인 또는 대응 |
|---|---|
| Bedrock Claude 호출 | 이 계정(<계정 ID>)에서 Haiku 4.5·Sonnet 5 호출 확인. 안 되면 LLM 단계를 가짜 응답으로 두고 결정 |
| 카드 결제 | 남은 자원이 바로 돈이 됨 → down 스크립트의 남은 자원 점검, 예산·이상 탐지 알림 |
| RDS 7일 자동 시작 | 회차 간격이 7일을 넘으면 guard 스크립트로 다시 정지 |
| CloudFront를 거친 SSE | 대기 시간이 데이터 조각 사이에도 적용되는지 확인 |
| 임베딩 모델·서빙 컨테이너 | x86 CPU에서 다국어 모델이 도는지 |
| Spring AI MCP 서버 | 쓰려는 Spring Boot·Kotlin 버전과 호환되는지 |
| 국내 곡 데이터가 얇음 | 앨범·아티스트 단위로 내려가고, 근거 단위와 신뢰도를 화면에 표시 |
| Takeout 대기 | 가상 기록으로 먼저 개발하고, 친구들에게는 미리 요청 |
| 일정 부족 | 12장의 줄이는 순서를 따름 |
| 개인정보 | 브라우저 필터, 7일·30일 삭제, 계정 없음, 즉시 삭제 버튼 |

## 15. 사전 준비 (계정·결제 작업은 사용자가 직접)

| 항목 | 상태 |
|---|---|
| 로컬 AWS 자격 증명 (계정 <계정 ID>, IAM 사용자) | 완료 (10-20) |
| 예산 알림, 비용 이상 탐지, IAM 결제 정보 접근, Cost Explorer | 완료 (10-20) |
| 루트 MFA 확인, 연락처 정보 확인 | 사용자 |
| Bedrock에서 Haiku 4.5·Sonnet 5 호출 확인 | 회차 ① 전 |
| Google Takeout 요청 (본인 + 써 줄 지인, "YouTube 및 YouTube Music → 기록", JSON) | 사용자 |
| Last.fm API 키, YouTube Data API 키 (Google Cloud 프로젝트) | 사용자 |
| GitHub 저장소 생성 | 회차 ④ 전 |
| Route 53 도메인 등록 (선택, 카드) | 사용자 |

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
