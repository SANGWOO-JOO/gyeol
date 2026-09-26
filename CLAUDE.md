# gyeol (내 음악 결)

유튜브 청취 기록이나 좋아하는 곡 몇 개를 넣으면 취향을 분석하고, 근거와 함께 결이 맞는 곡을 추천하는 서비스. EKS·AI 학습용 사이드 프로젝트.

- 설계 문서: `docs/design-v3.md` (작업 전 반드시 먼저 읽는다. 구조·데이터 모델·AI·인프라·일정·위험이 모두 여기 있다)
- 목적: ① 면접에서 말할 EKS·AI 경험(실측 수치 포함) ② 실제 사용자 5~10명
- 기간: 2026-09-27 ~ 10-04. 10-04에 AWS 자원 전체 destroy (무료 플랜 종료 10-05)
- 예산: AWS 크레딧 $150 안 (도메인 등록은 크레딧 불가, 카드)

## 작업 규칙

- **설계 먼저, 승인 후 구현.** 새 작업은 설계(구조, 데이터 흐름, 모듈)를 먼저 응답 본문으로 제시하고, 사용자가 "진행"처럼 명시적으로 지시한 뒤에 파일 생성·수정과 명령 실행을 시작한다.
- **원격 저장소 작업은 사용자가 직접 한다.** `git push`·`pull`·`fetch`, `gh`, GitHub API는 쓰지 않는다. 커밋은 사용자가 요청할 때만 한다.
- **비용이 드는 AWS 작업은 실행 전에 확인받는다.** `terraform apply`, 리소스 생성, 유료 API 호출이 해당된다. `destroy`도 대상 스택을 확인받고 실행한다.
- 응답과 문서는 한국어로 쓴다.
- 확인하지 않은 가격·수치·API 동작은 추정이라고 밝힌다.

## 저장소 구조 (모노레포)

```
gyeol/
 ├─ docs/              설계, 실험 기록
 ├─ infra/foundation   Terraform 유지 스택 (Route 53, ACM, CloudFront, S3, ECR, VPC, RDS, SQS 등)
 ├─ infra/cluster      Terraform 매일 부수는 스택 (NAT, EKS, 노드, 애드온)
 ├─ deploy/            Helm·Kustomize, Argo CD 앱 정의
 ├─ backend/           Kotlin + Spring Boot, Gradle 멀티 모듈 (core, api, worker, external-fetcher, mcp-server)
 ├─ web/               React + TypeScript + Vite
 └─ tools/             가상 Takeout 생성기, k6 스크립트
```

- 이 폴더는 자체 git 저장소다(`main` 브랜치). 홈 폴더(`~`)도 별도 git 저장소이므로, 이 폴더 밖에서 git 명령을 쓰지 않는다.
- GitHub 원격 저장소는 아직 없다. 사용자가 만들어 연결한다 (GitHub Actions·Argo CD를 붙이는 10/2 전까지).

## 확정된 선택

| 항목 | 선택 |
|---|---|
| 리전 | 서울(ap-northeast-2). CloudFront용 ACM 인증서만 us-east-1 |
| 프론트 배포 | S3 + CloudFront, `/api/*`는 `origin-api.<도메인>`의 ALB로 전달 |
| 도메인 | Route 53 저가 확장자 (**이름 미정**, 대안 is-a.dev). 등록 전에는 CloudFront 기본 주소로 진행 |
| LLM | Bedrock Claude. 대량 작업(제목 정리, 곡 설명)은 Haiku 4.5(`anthropic.claude-haiku-4-5`), 추천 이유와 대화는 Sonnet 5(`anthropic.claude-sonnet-5`). Anthropic Java SDK + `BedrockMantleBackend` |
| Bedrock 제약 | structured outputs 미지원 → tool use 스키마로 받고 자체 검증. citations·프롬프트 캐싱은 지원 |
| 임베딩 | 클러스터 안 CPU 다국어 소형 모델 (후보, 첫날 확인) |
| 노드 | x86 (시스템 t3.medium × 2, 워커는 Karpenter 스팟) |
| 데이터 보관 | 청취 기록 원본 7일, 분석 결과 30일, 즉시 삭제 버튼, 계정 없이 토큰 링크 |
| 운영 | 9/27~10/1 개발 기간은 밤마다 `cluster` 스택 destroy, 10/2~10/4 실사용 기간은 계속 켜 둠 |

## 지켜야 할 외부 조건

- YouTube: 오디오 내려받기 금지. Data API는 음악 여부 판정(`videos.list`)에만 쓰고, 파생 데이터를 만들지 않으며, 저장 시 30일 안에 갱신 또는 삭제. 일반 영상 제목은 서버로 보내지 않는다(브라우저 1차 필터).
- Last.fm: 비상업, 출처 표시와 링크, 초당 1회 정도.
- MusicBrainz: 앱을 알리는 User-Agent 헤더, 초당 1회.
- Wikipedia·Last.fm 위키: CC BY-SA, 출처 표시, 인용은 짧게.
- 가사와 음악 웹진 리뷰는 쓰지 않는다.
- 외부 API 호출은 `external-fetcher` 한 곳에서만 하고 API별로 속도를 제한한다.
- 부하 테스트에서는 LLM을 가짜 응답으로 바꾼다.

## 현재 상태 (2026-09-26)

- 설계안 v3 확정, 모노레포로 결정, `git init` 완료. 코드와 인프라는 아직 없음.
- 다음 작업: `docs/design-v3.md` 12장 일정의 9/27 인프라(Terraform `foundation` 스택)부터. 먼저 설계를 제시하고 승인받는다.
- 사용자 사전 준비 상태 미확인: 로컬 AWS 자격 증명, Route 53 도메인 등록, AWS Budgets 알림, Bedrock Haiku 4.5 호출 확인, Takeout 요청, Last.fm·YouTube API 키, GitHub 저장소.
