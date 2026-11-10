# 단일 서버 운영

상태: 측정 완료 (2026-11-10), 서버 미구축

EKS는 구축·검증 뒤 바로 내리고, 상시 운영은 비용이 적은 단일 서버에서 한다. 단일 서버는 k3s에 같은 Helm 차트(`deploy/helm/gyeol`)를 올린다. k3d 검증이 그대로 단일 서버 배포 리허설이 되고, 배포 설정을 하나로 유지할 수 있다.

## k3d 검증 (2026-11-10)

`scripts/k3d-up.sh` (서버 1 + 에이전트 1, DB·LocalStack은 클러스터 밖 compose, LLM은 fake).

| 항목 | 결과 |
|---|---|
| 빌드 → 배포 | 186초, 파드 5개(api·fetcher·worker·web·embed) 재시작 0 |
| worker | 파드 하나가 모든 역할 처리 (차트 기본값 `role: parse,identify,cover,enrich,recommend,guess,artist`), SQS 리스너 11개 시작 |
| 마이그레이션 | V20까지 적용 상태 확인 (같은 DB를 로컬 실행이 먼저 적용, 빈 DB 적용은 통합 테스트로 확인) |
| 입구(localhost:18000) | 결과 페이지·가수 순위·가수 이미지·채널 이미지·표지 모두 200, S3(LocalStack)에서 읽음 |
| 실제 Takeout 업로드 | 음악 기록 31,296건, 업로드 → 큐가 모두 빌 때까지 428초, worker 오류 로그 0 |
| 가수 순위 상위 50명 | Commons 40 · YouTube 7 · 대표 앨범 표지 2 · 글자 타일 1 (로컬 실행과 같음) |

428초는 전역 캐시가 찬 상태이고 LLM 호출이 없어서 처음 사용자 시간(약 20분)과 비교할 수 없다.

## 메모리 (맥북 M1, 15초 간격, 업로드 처리 약 10분 포함)

| 대상 | 대기 | 처리 중 최대 |
|---|---|---|
| embed (Python 임베딩) | 801Mi | 801Mi (이번 처리에서 쓰이지 않음) |
| worker | 284Mi | 383Mi |
| api | 243Mi | 275Mi |
| fetcher | 169Mi | 208Mi |
| web | 12Mi | 12Mi |
| 앱 파드 합계 | 1,509Mi | 1,679Mi |
| k3s 시스템 파드 (CoreDNS·Traefik·metrics-server 등) | 약 65Mi | — |
| PostgreSQL (compose) | 160Mi | 277Mi |
| LocalStack (compose, 운영에서는 SQS·S3) | 189Mi | 221Mi |

파드 값은 `kubectl top pods`, PostgreSQL·LocalStack은 `docker stats`. k3s 자체(컨트롤 플레인·containerd) 몫은 노드 컨테이너 메모리에서 파드 합계를 뺀 값으로 약 0.4~0.7GiB로 본다 (페이지 캐시가 섞인 추정).

단일 서버 예상: 앱 파드 1.7 + PostgreSQL 0.3 + k3s 시스템 0.07 + k3s 자체 0.5~0.7 + 운영체제 약 0.3 = **약 2.9~3.1GiB**.

- 4GB(t4g.medium): 약 1GB 여유.
- 2GB(t4g.small): 부족. embed를 0개로 두면 약 2.1~2.3GiB로 줄지만 스왑이 있어야 겨우 돈다. embed는 추천(곡 설명 벡터)에서만 쓰이고, 청취 기록 업로드는 지금 분석까지만 자동이라 쓰이지 않는다. 추천 버튼을 붙이면 다시 필요하다.

## 비용 (서울 리전, 2026-11-10 조회)

EC2 가격 (최근 7일 스팟 기록, Linux, 월 730시간):

| 구분 | 시간당 | 월 |
|---|---|---|
| t4g.medium 온디맨드 | 0.0416달러 | 약 30.4달러 |
| t4g.medium 스팟 2d | 0.0113~0.0121달러 | 약 8.2~8.8달러 (71~73% 할인) |
| t4g.medium 스팟 2a·2b·2c | 0.0151~0.0172달러 | 약 11.0~12.6달러 (59~64% 할인) |
| t4g.small 스팟 2d | 0.0045~0.0054달러 | 약 3.3~3.9달러 |

t4g.medium 스팟(2d) 기준 월 합계 (추정):

| 항목 | 월 |
|---|---|
| EC2 | 약 8.5달러 |
| EBS gp3 30GB | 약 2.7달러 |
| 공인 IPv4 | 약 3.6달러 |
| SQS (리스너 11개 대기 요청 월 약 280만 건, 무료 100만 건 초과분) | 약 0.7달러 |
| S3 (표지·가수 이미지·DB 백업) | 0.5달러 미만 |
| 데이터 전송 (월 100GB 무료) | 0 |
| 소계 / 부가세 10% 포함 | 약 16달러 / 약 17.5달러 |

별도: Bedrock은 쓴 만큼 (처음 사용자 업로드 1회 약 0.25달러, Haiku 4.5). 도메인을 쓰면 월 약 1.5달러.

EKS를 상시로 두지 않는 이유: 컨트롤 플레인만 월 약 73달러, ALB·NAT 게이트웨이도 필요하다. 단일 서버는 k3s 기본 Traefik이 입구를 맡고 공용 서브넷에 두어 NAT가 없다.

## 정한 것 / 남은 것

- 정함: k3s + 같은 Helm 차트, t4g.medium 스팟, SQS·S3·Bedrock은 AWS 서비스 그대로, 서버에 IAM 역할.
- 스팟 회수 대비: 회수 시 정지(EBS 유지) + 요청 유지로 자동 재시작, 처리 중이던 작업은 SQS 메시지로 이어 간다.
- 정함 (11-10): PostgreSQL은 차트 안 StatefulSet(`postgres.enabled`, 같은 `pgvector/pgvector:pg17`), 데이터는 k3s local-path 볼륨(서버 EBS), 매일 `pg_dump` → S3 CronJob. 값 파일 `values-single.yaml`.
- 남음: Terraform, 생성 직전 스팟 가격 재조회로 가용 영역 선택.

## 클러스터 안 PostgreSQL 확인 (k3d, 2026-11-10)

`values-local.yaml`에 `postgres.enabled=true`, `postgres.backup.enabled=true`를 더해 올렸다.

| 항목 | 결과 |
|---|---|
| 빈 DB 마이그레이션 | V1~V20 20건 적용 (0.17초), 확장 vector·pg_trgm·fuzzystrmatch |
| 앱 기동 순서 | Java 파드가 `wait-db` 초기 컨테이너로 DB 준비를 기다림. 처음에는 UID 1000에 사용자 이름이 없어 `pg_isready`가 "no attempt"로 멈춰서 `-U`를 붙였다 |
| 백업 | CronJob을 한 번 수동 실행 → 덤프 64KB가 S3(LocalStack)에 올라감 |
| 복원 | 그 덤프를 새 DB에 `pg_restore` → 마이그레이션 기록 20건 확인 |

확인 뒤 k3d는 원래 구성(맥북 compose DB)으로 되돌리고 시험 볼륨·덤프는 지웠다.

## Terraform에서 챙길 것

- 서버 메타데이터(IMDSv2) 홉 제한을 2로: 파드가 서버 IAM 역할 자격 증명을 받으려면 필요하다.
- 업로드 버킷 CORS (브라우저가 서명 URL로 직접 올림), 업로드 → parse 큐 객체 생성 알림.
- 백업 버킷은 업로드 버킷과 분리, 수명 주기 규칙으로 오래된 덤프 삭제 (예: 14일).
- EBS 루트 볼륨은 "서버 종료 시 삭제 안 함", 스팟 회수 시 정지.
- IAM 역할 권한: SQS(큐들), S3(업로드·표지·백업 버킷), Bedrock(Converse 호출 모델).
- 줄일 여지: SQS 대기 요청 간격을 20초로 늘리면 요청 수 절반, 시연하지 않는 기간에는 서버 정지(EBS·IP 약 6달러만 남음).
