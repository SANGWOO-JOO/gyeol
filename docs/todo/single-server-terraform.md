# 단일 서버 Terraform 설계 (2026-11-14, 결정됨)

상태: 설계만. 코드·`plan`·`apply` 없음. 이 문서를 승인받은 뒤 `infra/terraform/single`을 만든다.

근거:
- `single-server.md`: 11-10 메모리 측정·비용 조회·정한 것·"Terraform에서 챙길 것"
- `deploy-decisions.md`: D1~D6, 도메인 `ourgyeol.com`(Cloudflare DNS), 선물 이미지 하루 10장
- `cicd.md` 2절 deploy: OIDC → SSM send-command → pg_dump·helm upgrade·rollout·헬스 체크
- `infra/terraform/base`: SQS 10쌍·S3 3개·앱 IAM 정책·예산 알림·SES
- Helm 차트 `values-single.yaml`: 클러스터 안 PostgreSQL·매일 백업 CronJob·ghcr 이미지

표기:
- **사실**: 저장소 코드나 이미 조회한 값으로 확인한 것
- **추정**: 일반 지식이나 문서 기억에 기댄 것
- **확인 필요**: 구현 전에 AWS·Cloudflare 문서나 콘솔에서 다시 볼 것

AWS 호출은 하지 않았다. 가격은 `single-server.md` 11-10 조회값을 다시 쓰고, 새 항목은 "확인 필요"로 둔다.

## 0. 요약

```
Cloudflare DNS (ourgyeol.com A 레코드, 프록시 끔)
        │ 80·443
        ▼
EC2 t4g.medium 스팟 (Ubuntu 24.04 arm64, 기본 VPC 공용 서브넷, 탄력적 IP)
  └ k3s (버전 고정) ─ Traefik(Let's Encrypt TLS, /api/admin·/admin IP 허용)
      └ Helm gyeol (values-single.yaml + 서버의 values-prod.yaml)
          api · worker(room 포함) · fetcher · web · embed · PostgreSQL(StatefulSet, local-path)
  └ 서버 IAM 역할: base 앱 정책 + SSM 관리 + Parameter Store 읽기 (+ 선물 이미지 모델 us-west-2)
        │
        ├ SQS·S3·Bedrock·SES (base 스택)
        └ SSM Parameter Store /gyeol/prod/* (사용자가 직접 넣음)

GitHub Actions (v* 태그) ── OIDC ──> gyeol-deploy 역할 ── ssm:SendCommand(이 서버만) ──> 배포 스크립트
```

결정이 필요한 것은 10절에 추천과 함께 모았다.

## 1. 스택 구성

| 스택 | 상태 키 | 내용 | 지우면 |
|---|---|---|---|
| `infra/terraform/bootstrap` | 로컬 | 상태 버킷 | — |
| `infra/terraform/base` (있음) | `base/terraform.tfstate` | SQS·S3·앱 IAM 정책·예산 알림·SES | 데이터·큐 사라짐, 잘 안 지움 |
| **`infra/terraform/single` (새로)** | `single/terraform.tfstate` | EC2·EIP·보안 그룹·서버 IAM 역할·OIDC 배포 역할·Bedrock 예산 자동 조치 | 서버만 사라짐. base는 남음 |
| `infra/terraform/eks` (나중) | `eks/terraform.tfstate` | EKS 회차 | — |

### base 출력 참조 (추천: `terraform_remote_state`)

- single 스택은 같은 상태 버킷의 `base/terraform.tfstate`를 `data "terraform_remote_state" "base"`로 읽는다.
  - 읽는 출력(**사실**, `base/outputs.tf`에 있음): `app_policy_arn`, `upload_bucket`, `cover_bucket`, `backup_bucket`, `queues`, `mail_from`, `mail_configuration_set`.
  - 장점: 버킷 이름(계정 ID가 들어감)을 손으로 옮기지 않는다.
- 대안: 출력값을 `terraform.tfvars`에 복사. 스택 사이 결합은 없지만 값이 어긋날 수 있다.

### 파일 배치 (예정)

| 파일 | 내용 |
|---|---|
| `main.tf` | provider(ap-northeast-2), 백엔드(`single/terraform.tfstate`), us-west-2 별칭 provider는 쓰지 않음(IAM은 전역) |
| `network.tf` | 기본 VPC·서브넷 조회, 보안 그룹, EIP |
| `ec2.tf` | AMI 조회(Ubuntu 24.04 arm64), 스팟 인스턴스, user-data |
| `iam.tf` | 서버 역할·인스턴스 프로필, Parameter Store 읽기, 선물 이미지 모델 |
| `deploy.tf` | GitHub OIDC 공급자, `gyeol-deploy` 역할 |
| `budget.tf` | Bedrock 예산 + 자동 조치(10절 S7 결정 뒤) |
| `user-data.sh.tftpl` | k3s·helm 설치, swap, 서버용 값 파일, 배포 스크립트 |
| `variables.tf`·`terraform.tfvars.example` | 가용 영역, 관리자 IP, 도메인, k3s 버전 등 (실제 tfvars는 git 제외) |
| `outputs.tf` | EIP 주소, 인스턴스 ID, 배포 역할 ARN (GitHub Variables에 넣을 값) |

## 2. 네트워크

### 2-1. VPC: 기본 VPC 공용 서브넷 (추천)

- NAT 게이트웨이를 두지 않는다(월 약 32달러 이상, **추정**). 공용 서브넷의 서버가 직접 인터넷에 나간다.
- 새 VPC를 만들면 서브넷·라우팅·IGW 코드가 늘지만 기본 VPC 의존이 없다. 결정 S3.
- 서브넷은 가용 영역 변수(`availability_zone`)로 고른다.

### 2-2. 가용 영역: 생성 직전 가격 조회

- 11-10 조회(**사실**): t4g.medium 스팟은 2d가 0.0113~0.0121달러/시간으로 가장 쌌고, 2a·2b·2c는 0.0151~0.0172달러였다.
- 스팟 가격은 바뀐다. `apply` 직전에 사용자가 아래 명령으로 다시 조회하고 `availability_zone`을 고른다(읽기만, 비용 없음).

```bash
aws ec2 describe-spot-price-history --region ap-northeast-2 --instance-types t4g.medium --product-descriptions "Linux/UNIX" --start-time "$(date -u -v-7d +%Y-%m-%dT%H:%M:%S)" --query 'SpotPriceHistory[].[AvailabilityZone,SpotPrice]' --output text | sort | awk '{a[$1]=a[$1]" "$2} END{for(k in a) print k, a[k]}'
```

- EBS는 가용 영역에 묶인다. 서버를 다른 영역으로 옮기려면 스냅숏 → 새 볼륨이 필요하다(**사실**, EBS 기본 성질). 처음 고른 영역을 오래 쓰게 된다.

### 2-3. 공인 IP: 탄력적 IP (추천, 결정 S1)

| 방식 | 스팟 정지·시작 때 주소 | 비용 (**확인 필요**, 2024-02부터 공인 IPv4 과금) |
|---|---|---|
| 탄력적 IP | 그대로 | 시간당 0.005달러 = 월 약 3.6달러. 서버가 정지돼 있어도 같은 값(**추정**) |
| 자동 할당 공인 IP | **바뀜** → Cloudflare A 레코드를 다시 고쳐야 함 | 실행 중에만 시간당 0.005달러(**추정**) |

- 스팟은 회수되면 정지됐다가 다시 시작한다(2-4). 자동 할당이면 그때마다 DNS가 깨진다.
- 정지 중 비용 차이는 월 몇 달러 안쪽이라 탄력적 IP를 추천한다.

### 2-4. 보안 그룹

| 방향 | 포트 | 출처 | 이유 |
|---|---|---|---|
| 들어옴 | 80 | 0.0.0.0/0 (IPv6 ::/0 선택) | Let's Encrypt HTTP-01, HTTPS로 넘김 |
| 들어옴 | 443 | 0.0.0.0/0 | 웹·API |
| 나감 | 전부 | 0.0.0.0/0 | AWS API·ghcr·Let's Encrypt·Last.fm·MusicBrainz 등 |

- SSH(22)는 열지 않는다. 접속은 SSM Session Manager로만 한다(서버 역할에 `AmazonSSMManagedInstanceCore`).
- k3s API(6443)도 열지 않는다. kubectl은 서버 안(SSM 세션)에서만 쓴다.
- Cloudflare 프록시를 켜면(결정 S4) 80·443 출처를 Cloudflare IP 대역으로 좁힐 수 있다.

## 3. 서버 (EC2)

### 3-1. 인스턴스

| 항목 | 값 | 근거 |
|---|---|---|
| 종류 | t4g.medium (arm64, 4GiB) | `single-server.md` 측정: 예상 사용 약 2.9~3.1GiB, 여유 약 1GB (**사실**) |
| 구매 | 스팟, `spot_instance_type = "persistent"`, `instance_interruption_behavior = "stop"` | 회수되면 정지(EBS 유지) → 용량이 생기면 자동 시작 (**추정**, Terraform `instance_market_options` 문서로 확인 필요) |
| 최고 가격 | 지정 안 함(온디맨드 가격이 상한) | 가격이 오르면 회수 대신 비싸게 계속 돈다. 예산 알림으로 본다 |
| AMI | Ubuntu 24.04 LTS arm64 (Canonical 공식, `data "aws_ami"`로 최신) | SSM 에이전트 기본 설치(**추정**), k3s 공식 지원 OS. 결정 S2 |
| 메타데이터 | `http_tokens = "required"`(IMDSv2), `http_put_response_hop_limit = 2` | 파드(컨테이너 안)가 서버 역할 자격 증명을 받으려면 홉 2 (**사실**, `single-server.md`) |
| 모니터링 | 기본(5분) | 상세 모니터링은 유료 |
| 종료 보호 | 켬(`disable_api_termination`) | 실수로 지우는 것 방지. `destroy` 때는 먼저 끔 |

- 스팟 인스턴스는 "정지" 동작에 `persistent` 요청이 필요하다(**추정**).
- AMI가 바뀌면 Terraform이 인스턴스를 다시 만들려 한다. `lifecycle { ignore_changes = [ami, user_data] }`로 막고, OS 갱신은 서버 안에서 한다.

### 3-2. EBS

| 항목 | 값 |
|---|---|
| 루트 볼륨 | gp3 30GB, 기본 3,000 IOPS·125MB/s(추가 요금 없음, **추정**), 암호화(기본 KMS 키) |
| 종료 시 | **삭제 안 함**(`delete_on_termination = false`, `single-server.md` 결정) |
| 들어가는 것 | OS, k3s, 이미지 캐시, local-path 볼륨(PostgreSQL 데이터 `/var/lib/rancher/k3s/storage`) |

- 비용: 월 약 2.7달러(**사실**, 11-10 조회).
- 주의: `delete_on_termination = false`면 `terraform destroy` 뒤에도 볼륨이 남아 계속 과금된다.
  - 서버를 완전히 내릴 때는 마지막 `pg_dump`가 S3에 있는지 확인하고 볼륨을 손으로 지운다. 절차를 운영 문서에 적는다.
  - 대안: 루트는 삭제, PostgreSQL용 데이터 볼륨을 따로 붙여 그것만 남긴다. 코드가 늘고 마운트·local-path 경로 설정이 필요하다. 결정 S8.
- 백업은 이미 있는 매일 `pg_dump` → S3(14일 보관) CronJob이 맡는다. 스냅숏(DLM)은 쓰지 않는다(비용·중복).

### 3-3. user-data (처음 한 번)

순서:
1. 패키지 갱신 최소, 시간대 Asia/Seoul.
2. swap 2GB 파일. 측정상 여유 약 1GB라 처리 몰림 때 OOM을 막는 안전장치다. 결정 S11.
3. **k3s 설치, 버전 고정**: `curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION=<변수 k3s_version> sh -s - --write-kubeconfig-mode 600 --disable servicelb`.
   - 버전은 k3d 검증 때와 같은 마이너(**확인 필요**: 구현 때 `k3d version`의 k3s 버전을 고정값으로).
   - servicelb는 끄고 Traefik은 hostPort 80·443(3-4)로 둔다.
4. helm(버전 고정) 설치, AWS CLI v2(arm64) 설치. Ubuntu에는 기본으로 없다(**추정**).
5. `/etc/gyeol/values-prod.yaml` 작성(Terraform `templatefile`): 도메인·base 버킷 이름·관리자 IP·메일 설정.
   - 비밀은 넣지 않는다. 비밀은 4절.
6. `/usr/local/bin/gyeol-deploy` 배포 스크립트 설치(6절).
7. Traefik 설정(`HelmChartConfig`): ACME·HTTP→HTTPS 넘김·로그(5절).

- user-data는 처음 부팅에만 돈다. 스팟 정지 → 시작 때는 k3s가 systemd로 다시 뜬다.
- Terraform `ignore_changes = [user_data]`라서, 고친 user-data는 서버에 자동 반영되지 않는다. 스크립트를 고치면 SSM으로 다시 넣는다(또는 배포 스크립트가 태그의 파일로 갱신).

### 3-4. Traefik 입구

- k3s 기본 Traefik을 쓴다. 메모리 측정값에 이미 포함돼 있다(k3s 시스템 파드 약 65Mi, **사실**).
- ServiceLB를 끄면 Traefik 서비스를 hostPort(80·443)로 노출하도록 `HelmChartConfig`에서 바꾼다(**추정**: k3s 문서로 확인 필요). servicelb를 켜 두는 것도 간단하다(klipper-lb가 hostPort를 대신 잡음). 구현 때 단순한 쪽으로 정한다.

## 4. 비밀값: Parameter Store → k8s Secret

| 이름 (SecureString) | 쓰는 곳 | 만드는 사람 |
|---|---|---|
| `/gyeol/prod/DB_PASSWORD` | PostgreSQL·api·worker | 사용자 (`openssl rand -base64 24` 등) |
| `/gyeol/prod/ADMIN_TOTP_KEY` | api 관리자 OTP 암호화 | 사용자 (`openssl rand -base64 32`) |
| `/gyeol/prod/LASTFM_API_KEY` | fetcher | 사용자 |
| `/gyeol/prod/YOUTUBE_API_KEY` | fetcher | 사용자 |

- **Terraform에 값을 넣지 않는다.** `aws_ssm_parameter`로 만들면 값이 상태 파일에 남는다(**사실**, Terraform 동작).
  - 사용자가 자기 터미널에서 `aws ssm put-parameter --type SecureString`으로 넣는다. 명령은 문서에만 적고 Claude는 실행하지 않는다.
- 암호화 키: AWS 관리형 키 `alias/aws/ssm`. 표준 파라미터는 무료(**추정**, 확인 필요).
- 서버 역할 권한:
  - `ssm:GetParametersByPath`·`ssm:GetParameters`: `arn:aws:ssm:ap-northeast-2:<계정>:parameter/gyeol/prod/*`
  - `kms:Decrypt`: `kms:ViaService = ssm.ap-northeast-2.amazonaws.com` 조건. AWS 관리형 키는 키 정책이 계정 주체를 허용하므로 IAM 정책만으로 될 것으로 본다(**추정**).
- 적용 방법(추천, 결정 S5): 배포 스크립트(6절)가 `helm upgrade` 전에 다음을 한다.
  1. `aws ssm get-parameters-by-path --path /gyeol/prod/ --with-decryption`
  2. `kubectl create secret generic gyeol-secrets --from-literal=… --dry-run=client -o yaml | kubectl apply -f -`
  - 값은 화면·로그에 찍지 않는다(`set +x`).
- 대안: External Secrets Operator. 자동 동기화가 되지만 파드가 하나 더 늘고(메모리 약 100MB, **추정**) 설정이 늘어난다. 4GiB 서버에는 과하다.
- **빈틈(사실, 11-14 확인)**: Helm 차트는 `DB_PASSWORD`·`LASTFM_API_KEY`·`YOUTUBE_API_KEY`만 Secret에서 읽는다. `ADMIN_TOTP_KEY`는 아직 api env에 연결돼 있지 않다.
  - 구현 때 `backend.yaml` api env에 `secretKeyRef ADMIN_TOTP_KEY`를 더한다(optional: 없으면 관리자 로그인 503).

## 5. 도메인·TLS (Cloudflare DNS)

### 5-1. Cloudflare 프록시: 끄기(DNS만) 추천 (결정 S4)

| | 프록시 끔 (회색 구름, 추천) | 프록시 켬 (주황 구름) |
|---|---|---|
| 인증서 | 서버 Traefik이 Let's Encrypt로 직접 받음(HTTP-01) | Cloudflare가 방문자 쪽 TLS. 서버에는 Cloudflare Origin 인증서(15년)나 Let's Encrypt(DNS-01, Cloudflare API 토큰 필요). SSL 모드 Full(strict) |
| 서버 IP | 공개됨 | 숨겨짐(보안 그룹을 Cloudflare 대역으로 좁힐 수 있음) |
| 방문자 IP | 그대로 서버에 옴 → 관리자 IP 허용·앱 IP 제한(`X-Forwarded-For` 첫 값)이 그대로 맞음 | Cloudflare IP로 옴 → Traefik `forwardedHeaders.trustedIPs`에 Cloudflare 대역, 관리자 허용은 `CF-Connecting-IP` 기준으로 다시 짜야 함 |
| 기타 | 단순. 장애 지점이 하나 적음 | DDoS 완화·캐시. 무료 요금제 요청 본문 100MB 제한(**추정**). Takeout은 S3 서명 URL로 직접 올리므로 영향 없음(**사실**, 업로드 설계) |

- 추천 이유: 첫 운영은 사용자가 적다. 관리자 IP 허용과 앱의 IP 제한이 지금 코드 그대로 맞는 쪽이 안전하다. 나중에 켜려면 Traefik 신뢰 대역·관리자 허용 기준을 함께 바꾼다.

### 5-2. Let's Encrypt (Traefik ACME)

- k3s의 Traefik `HelmChartConfig`에 `certificatesResolvers.le.acme`를 설정한다.
  - httpChallenge entryPoint web
  - 저장소: 지속 볼륨 `/data/acme.json`(Traefik `persistence.enabled`, local-path)
- Ingress에 `traefik.ingress.kubernetes.io/router.tls.certresolver: le`와 `tls.hosts: [ourgyeol.com]`을 단다.
  - Helm `ingress.host`·`ingress.tls` 값이 필요하다. 지금 차트에는 tls가 없다(**사실**).
- HTTP→HTTPS 넘김: Traefik `ports.web.redirectTo`.
- 처음 발급은 DNS A 레코드가 EIP를 가리킨 뒤에 된다. 순서는 9절.
- 대안 cert-manager: 표준적이지만 파드 3개·메모리가 늘어난다(**추정**). Traefik 내장 ACME로 충분하다.

## 6. 배포 (`gyeol-deploy` 역할과 서버 스크립트)

### 6-1. GitHub OIDC 배포 역할

- OIDC 공급자 `token.actions.githubusercontent.com`.
  - 계정에 이미 있으면 data로 참조(**확인 필요**: 다른 프로젝트에서 만들었는지).
  - audience `sts.amazonaws.com`.
- 신뢰 정책(**추천**):
  - `token.actions.githubusercontent.com:aud = sts.amazonaws.com`
  - `token.actions.githubusercontent.com:sub`가 `repo:SANGWOO-JOO/gyeol:ref:refs/tags/v*`일 때만(StringLike)
- 롤백(`workflow_dispatch`)은 브랜치(main)에서 돌아 sub가 `ref:refs/heads/main`이 된다. 위 조건으로는 막힌다. 결정 S6:
  - (추천) GitHub Environment `production`을 쓰고 sub를 `repo:SANGWOO-JOO/gyeol:environment:production`으로. 배포·롤백 작업 모두 그 Environment로 돌린다. Environment 보호 규칙으로 태그만·승인자 지정이 가능하다.
  - 대안: 롤백도 태그를 다시 push하는 방식만 쓰고 `refs/tags/v*`만 허용.
- 권한(그 인스턴스만):
  - `ssm:SendCommand`: `arn:aws:ec2:ap-northeast-2:<계정>:instance/<id>`와 `arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript`
  - `ssm:GetCommandInvocation`·`ssm:ListCommandInvocations`: 리소스 수준 제한이 안 되는 것으로 알려져 있어 `*`(**확인 필요**)
  - 그 밖(EC2·S3·IAM) 없음.
- GitHub 저장소 Variables: `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `SERVER_INSTANCE_ID`(Terraform 출력). 비밀(Secrets)은 쓰지 않는다.

### 6-2. 서버 배포 스크립트 `gyeol-deploy <태그>` (release.yml deploy 작업이 SSM으로 부름)

1. 태그 형식 확인(`^v[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9.]+)?$`). 아니면 실패.
2. 백업: `kubectl create job --from=cronjob/gyeol-postgres-backup predeploy-<태그>` → 완료 대기. 실패하면 배포 중단.
3. 차트 받기: 공개 저장소 태그 압축본 `https://github.com/SANGWOO-JOO/gyeol/archive/refs/tags/<태그>.tar.gz` → `deploy/helm/gyeol`. 토큰 불필요. 결정 S9.
4. Secret 갱신(4절).
5. `helm upgrade --install gyeol <차트> -n gyeol -f values-single.yaml -f /etc/gyeol/values-prod.yaml --set image.tag=<태그> --wait --timeout 10m`
6. `kubectl rollout status` (api·worker·fetcher·web·embed)
7. 출력 마지막 줄에 결과. GitHub 쪽은 `https://ourgyeol.com/api/health` UP을 확인한다(release.yml에서).

- 실패하면 스크립트가 0이 아닌 값으로 끝나고 워크플로가 실패한다.
- 자동 롤백은 하지 않는다. 마이그레이션이 앞으로만 가므로 사람이 판단한다(`cicd.md`). `helm rollback`은 이미지만 되돌리고 DB는 그대로다.

## 7. 서버 IAM 역할

| 붙일 정책 | 내용 |
|---|---|
| base `gyeol-app` (있음) | SQS 10쌍, S3 3개 버킷, Bedrock Haiku(global 추론 프로필), SES(도메인 있을 때) |
| `AmazonSSMManagedInstanceCore` (AWS 관리형) | SSM 에이전트·Session Manager·send-command |
| 인라인 `gyeol-server-params` | 4절의 Parameter Store 읽기 + kms:Decrypt |
| 선물 이미지 모델 (추가) | `bedrock:InvokeModel` on `arn:aws:bedrock:us-west-2::foundation-model/stability.stable-image-core-v1:1` |

- 선물 이미지 모델:
  - base 정책(`bedrock_models`·리전)에는 없다(**사실**, `deploy-decisions.md` 2절).
  - base에 넣을지 single에 넣을지: EKS도 쓸 권한이라 **base에 변수로 추가**하는 쪽을 추천한다. 구현 때 base 변경도 같이 한다.
  - Stability 모델은 AWS Marketplace 구독이 필요하다(**사실**, `room-plan.md` ⑯ 확인 기록).
  - 처음 호출 때 `aws-marketplace:Subscribe`·`ViewSubscriptions` 권한이 필요하다는 자료가 있다(**확인 필요**). 구독은 사용자가 콘솔에서 미리 하는 것을 추천한다(9절).
- 파드는 서버 역할 자격 증명을 메타데이터(IMDSv2, 홉 2)로 받는다. 파드별 권한 분리는 단일 서버에서는 하지 않는다. EKS 회차에서 Pod Identity로 나눈다.

## 8. 관리자 경로 IP 허용

- Traefik `Middleware`(`traefik.io/v1alpha1`, `ipAllowList.sourceRange: [<관리자 IP>/32]`)를 만든다(**추정**: k3s 번들 Traefik 버전의 CRD 이름 확인 필요, v2는 `ipWhiteList`).
- Helm에 값 `admin.allowedIps`(목록)를 더한다. 비어 있으면 관리자 Ingress를 만들지 않는다 = 경로 자체를 닫는다.
  - 관리자 전용 Ingress: `/api/admin`(→ api), `/admin`(→ web)에 위 미들웨어를 단다.
  - 기본 Ingress의 `/api`·`/`보다 경로가 길어서 먼저 걸린다.
- 웹 `/admin` 화면은 정적 파일이라 막아도 자산(`/assets/*`)은 공개다. 데이터는 `/api/admin`에서만 오므로 충분하다(**사실**, 관리자 API는 쿠키 + OTP).
- 관리자 IP는 `terraform.tfvars`(git 제외) → `/etc/gyeol/values-prod.yaml`로 넣는다.
  - 집 IP가 바뀌면 SSM으로 값 파일을 고치고 `helm upgrade`를 다시 한다(또는 다음 태그 배포).
- 프록시를 켜면(5-1) 출처가 Cloudflare라 이 방식이 그대로는 맞지 않는다.

## 9. 사용자가 직접 할 일 (순서)

| # | 할 일 | 어디서 | 막히는 것 |
|---|---|---|---|
| 1 | `ourgyeol.com` 구매(구매 직전 미등록 재확인, 갱신가 확인) | Cloudflare Registrar | 메일·TLS·DNS 전부 |
| 2 | base `terraform.tfvars`에 `mail_domain = "ourgyeol.com"`, `budget_email` → base apply | 맥북 터미널 | — |
| 3 | base 출력 `ses_dns_records`(DKIM 3개·MAIL FROM MX/SPF·DMARC)를 Cloudflare DNS에 입력 → SES에서 도메인 인증 확인 | Cloudflare·AWS 콘솔 | 메일 발송 |
| 4 | SES 샌드박스 해제 신청(도메인 인증 뒤, 사용 사례 설명) | AWS 콘솔 | 등록 안 된 주소로 발송. 답은 보통 하루 안이라는 안내가 있으나 자료마다 다름(**확인 필요**) |
| 5 | Bedrock: Haiku 4.5 모델 접근(이미 씀), Stability Stable Image Core 구독(us-west-2) | AWS 콘솔 | 선물 새 그림(구독 전에는 기본 캐릭터로 대신함) |
| 6 | Parameter Store에 비밀 4개 넣기(4절, `ADMIN_TOTP_KEY`는 새로 생성) | 맥북 터미널 | 서버 첫 배포 |
| 7 | 스팟 가격 조회 → `availability_zone`, 관리자 IP → single `terraform.tfvars` → **single apply** | 맥북 터미널 | — |
| 8 | 출력 EIP를 Cloudflare A 레코드 `ourgyeol.com`(+`www` 선택)에 입력, 프록시 끔 | Cloudflare | TLS 발급 |
| 9 | GitHub: ghcr 패키지 5개 Public으로, 저장소 Variables 3개(6-1), Environment `production`(S6) | GitHub 설정 | 배포 워크플로 |
| 10 | 첫 태그 push → 배포 → `https://ourgyeol.com` 확인 | 관리 세션과 함께 | — |
| 11 | 음악 지도 데이터 옮기기(D4): 로컬 덤프 → S3 → 서버에서 `pg_restore`(지도 표만) | SSM 세션 | 지도 화면 |
| 12 | 관리자 계정: 맥북에서 `ADMIN_TOTP_KEY=<6번 값> uv run --directory tools/admin-account python make_admin.py` → 인증 앱 등록 → INSERT를 서버 DB에서 실행(SSM 세션 `kubectl exec … psql`) | 맥북·SSM 세션 | 관리자 페이지 |

- 2~4와 6~8은 서로 기다리지 않는다. 1이 먼저다.
- 비용이 생기는 단계는 2(거의 0)·7(월 약 17.5달러부터)이다. 7 직전에 한 번 더 확인받는다.

## 10. 결정 (2026-11-14 사용자: S1~S12 모두 추천안, S7 한도 월 15달러)

| # | 결정 | 추천 | 다른 선택 |
|---|---|---|---|
| S1 | 공인 IP | **탄력적 IP**: 스팟 재시작에도 주소 유지, 비용 차이 미미 | 자동 할당(재시작마다 DNS 수정) |
| S2 | OS | **Ubuntu 24.04 LTS arm64**: k3s 공식 지원, SSM 에이전트 기본 | Amazon Linux 2023(AWS CLI 기본, k3s 지원 목록 확인 필요) |
| S3 | VPC | **기본 VPC 공용 서브넷** | 새 VPC(코드 증가, 기본 VPC 의존 없음) |
| S4 | Cloudflare 프록시 | **끔** + Traefik Let's Encrypt | 켬 + Origin 인증서(IP 숨김, 관리자 허용·IP 제한 재설계) |
| S5 | 비밀 전달 | **배포 스크립트가 Parameter Store → Secret** | External Secrets Operator(메모리·설정 증가) |
| S6 | 배포 역할 신뢰 조건 | **GitHub Environment `production`**(태그 배포·롤백 둘 다, 보호 규칙 가능) | `refs/tags/v*`만(롤백도 태그 push로) |
| S7 | Bedrock 비용 상한 | **Bedrock만 거른 월 예산 + 자동 조치**: 실제 비용이 한도(**월 15달러로 결정**)를 넘으면 서버 역할에 Bedrock 거부 정책을 붙임. 앱의 하루 10장·업로드 제한이 1차, 이건 사고 대비 2차 | 알림만(지금 base 예산) |
| S8 | EBS 보존 | **루트 하나, 종료 시 삭제 안 함**(11-10 결정 그대로) + 내릴 때 손으로 정리 절차 | 데이터 볼륨 분리(루트는 삭제, DB만 보존) |
| S9 | 서버가 차트를 받는 곳 | **GitHub 태그 압축본**(공개, 토큰 불필요) | 릴리스에 차트 묶음(.tgz)을 첨부 |
| S10 | 관리자 IP | 집 IP /32 하나, tfvars(git 제외) | 여러 개, 또는 관리자 경로를 평소 닫아 두고 필요할 때만 열기 |
| S11 | swap | **2GB 파일** | 없음(OOM 위험), 4GB |
| S12 | k3s 버전 | k3d 검증과 같은 마이너로 고정 | 최신 안정판 |

### S7 메모 (AWS Budgets 자동 조치)

- 예산 자동 조치(`aws_budgets_budget_action`)는 한도를 넘으면 IAM 정책을 역할에 붙이거나 SCP·EC2 정지를 할 수 있다(**추정**: Terraform 자원 문서로 확인 필요).
- 실행에는 Budgets가 맡을 실행 역할이 필요하다.
- 한계(**추정**):
  - 비용 데이터는 하루 몇 번 갱신이라 몇 시간 늦다. 실시간 차단이 아니다.
  - 그래서 앱의 하루 10장 한도가 1차 방어다.
- 무료 범위: 예산은 계정당 처음 2개 무료라는 기존 기록이 있다(`budget.tf` 주석, **사실**). 자동 조치가 있는 예산의 과금은 **확인 필요**.
- 걸리면: Bedrock 호출이 막힌다.
  - 청취 분석·새 가수 설명은 실패하고, 코드 맞추기는 점수만 나오고 이유 없이 끝난다(NO_REASON).
  - 선물은 기본 캐릭터로 대신한다(코드상 실패 → 기본, **사실**).
  - 풀 때는 사용자가 콘솔에서 조치를 되돌린다.

## 11. 비용 (월, 서울, 부가세 별도 → 마지막 줄에 포함)

| 항목 | 월 | 근거 |
|---|---|---|
| EC2 t4g.medium 스팟(2d) | 약 8.5달러 | 11-10 조회(**사실**), 생성 직전 재조회 |
| EBS gp3 30GB | 약 2.7달러 | 11-10(**사실**) |
| 탄력적 IP(공인 IPv4) | 약 3.6달러 | 시간당 0.005달러(**확인 필요**) |
| SQS | 약 1.3달러 | 리스너가 11개 → 17개(10쌍 + 실패 큐 리스너)로 늘어 대기 요청 월 약 430만 건으로 다시 추정. 100만 건 무료, 100만 건당 0.4달러(**추정**). 대기 간격 20초로 줄일 여지 |
| S3(표지·가수 이미지·캐릭터·백업) | 0.5달러 미만 | 11-10(**추정**) |
| Parameter Store 표준 4개 + KMS(AWS 관리형) | 0 | 표준 파라미터 무료(**확인 필요**) |
| 도메인 `ourgyeol.com` | 약 0.9달러 | 연 약 10.5달러(구매 때 확인) |
| SES | 0에 가까움 | 1,000통당 0.10달러(**추정**) |
| 데이터 전송(월 100GB 무료) | 0 | 11-10 |
| **소계** | **약 17.5달러** | |
| **부가세 10% 포함** | **약 19.3달러** | |
| 쓴 만큼 | Bedrock Haiku(업로드 1회 약 0.25달러), 선물 이미지 최대 하루 10장 × 약 0.04달러 = 월 최대 약 12달러 | 이미지 단가 **확인 필요** |

- 시연하지 않는 기간에는 서버를 정지한다. EBS 2.7 + EIP 3.6 = 약 6.3달러만 남는다(**추정**).
- 11-10 표(부가세 포함 약 17.5달러)보다 약 1.8달러 늘었다: SQS 리스너 증가(+0.6)와 도메인(+0.9), 그 부가세.

## 12. 구현 때 함께 고칠 저장소 빈틈 (사실, 11-14 확인)

- Helm:
  - `ADMIN_TOTP_KEY` secretKeyRef(4절)
  - `ingress.tls`·certresolver 주석(5-2)
  - 관리자 전용 Ingress + Middleware(8절)
  - api `MAIL_MODE=ses`·`MAIL_FROM`·`MAIL_CONFIG_SET`를 `values-prod.yaml`에서
  - `WEB_ORIGIN=https://ourgyeol.com`
  - `ADMIN_SECURE_COOKIE=true`, `ROOM_SECURE_COOKIE=true`
- base: `bedrock_models`에 이미지 모델·리전(7절), `web_origins`에 `https://ourgyeol.com`(업로드 버킷 CORS).
- release.yml: deploy 작업(OIDC → SSM `gyeol-deploy <태그>` → 헬스 체크), rollback(`workflow_dispatch`).
- Helm: `/api` Ingress 분리 + `gyeol-api-ratelimit` 미들웨어(15-1), 값 `rateLimit.*`.
- api: 검색 2개에 `IpRateLimiter`(15-2), 배포 뒤 `X-Forwarded-For` 위조 확인(15-2).
- 도구: `tools/db-tunnel.sh`(14-3, ClusterIP 조회 + 포트 포워딩, 로컬 포트 25432).
- 문서: `single-server.md`의 "맥북에서 빌드해 서버 k3s에 직접 넣는다"는 ghcr로 바뀜.

## 13. 기록

| 날짜 | 내용 |
|---|---|
| 11-14 | 초안 (갈래 1). AWS 호출 없음, 가격은 11-10 조회값 + 확인 필요 표시 |
| 11-14 | 14절 운영 DB 접속(SSM 포트 포워딩, 읽기 전용 계정) 추가 |
| 11-14 | 15절 요청 수 제한(Traefik RateLimit, 앱 검색 제한, X-Forwarded-For 확인) 추가 |

## 14. 운영 DB 접속 (맥북 → SSM 포트 포워딩)

PostgreSQL은 클러스터 안 서비스 `gyeol-postgres`(ClusterIP, 5432)라 밖에서 바로 닿지 않는다(**사실**, `templates/postgres.yaml`). 보안 그룹도 80·443만 연다(2-4). 5432를 열지 않고 SSM 포트 포워딩으로 맥북 로컬 포트를 서버 안 DB에 잇는다.

### 14-1. 방식 비교

| 방식 | 내용 | 판단 |
|---|---|---|
| **SSM 포트 포워딩 (추천)** | 서버의 SSM 에이전트가 `gyeol-postgres` ClusterIP:5432로 대신 연결 | 포트를 열지 않는다. 접근은 IAM으로 통제하고 세션 기록이 남는다 |
| SSM 터널 + kubectl | k3s API(6443)를 터널로 잇고 맥북에서 `kubectl port-forward` | 된다. kubeconfig를 맥북으로 옮겨야 해서 한 단계 더 |
| 5432를 집 IP에만 열기 | 보안 그룹 + NodePort | 쓰지 않는다. DB가 인터넷에 드러나고, 집 IP가 바뀌면 끊긴다 |

- 서버는 k3s 노드라 ClusterIP로 갈 수 있다. 그래서 호스트의 SSM 에이전트가 원격 호스트로 ClusterIP를 쓸 수 있다(**추정**: kube-proxy iptables 규칙. 구현 뒤 실제로 확인).
- 필요한 권한: 서버 역할 `AmazonSSMManagedInstanceCore`(7절, 이미 계획), 맥북 사용자 `ssm:StartSession`(지금 Admin이라 있음).
- 비용: Session Manager 자체는 추가 요금이 없다고 알고 있다(**확인 필요**).

### 14-2. 맥북 준비 (한 번)

```bash
brew install --cask session-manager-plugin
```

- 로컬 compose DB가 맥북 `15432`를 쓰므로(`values-local.yaml`), 운영 터널은 **`25432`**를 쓴다. 둘을 헷갈리지 않게 포트로 나눈다.

### 14-3. 접속 절차

1. ClusterIP 조회 (서비스를 지웠다 다시 만들면 바뀌므로 매번 조회)

```bash
aws ssm send-command --instance-ids <인스턴스ID> --document-name AWS-RunShellScript --parameters 'commands=["k3s kubectl get svc gyeol-postgres -A -o jsonpath={.items[0].spec.clusterIP}"]' --query Command.CommandId --output text
```

```bash
aws ssm get-command-invocation --instance-id <인스턴스ID> --command-id <위 CommandId> --query StandardOutputContent --output text
```

2. 터널 열기 (창을 열어 둔 동안 유지)

```bash
aws ssm start-session --target <인스턴스ID> --document-name AWS-StartPortForwardingSessionToRemoteHost --parameters '{"host":["<ClusterIP>"],"portNumber":["5432"],"localPortNumber":["25432"]}'
```

3. DB 도구(DBeaver·DataGrip·psql)로 `localhost:25432`, DB `gyeol`, 사용자는 아래 읽기 전용 계정 `gyeol_ro`.

- 인스턴스 ID는 single 출력(`outputs.tf`)에 있다.
- 1·2를 묶은 스크립트 `tools/db-tunnel.sh`를 구현 때 만든다(12절). 인스턴스 ID는 태그 `Name=gyeol-single`로 찾는다.

### 14-4. 읽기 전용 계정 (추천)

운영 DB에 맥북으로 붙을 때 실수로 지우거나 고치지 않게, 조회용 계정을 따로 둔다. 앱 계정 `gyeol`(`values.yaml` `db.user`)은 맥북에서 쓰지 않는다.

- 비밀번호: Parameter Store `/gyeol/prod/DB_RO_PASSWORD`(SecureString, 사용자가 직접 넣음, 4절과 같은 방식)
- 만들기: 첫 배포 뒤 SSM 세션에서 한 번 (`k3s kubectl exec -it gyeol-postgres-0 -- psql -U gyeol -d gyeol`)

```sql
CREATE ROLE gyeol_ro LOGIN PASSWORD '<DB_RO_PASSWORD 값>';
GRANT CONNECT ON DATABASE gyeol TO gyeol_ro;
GRANT USAGE ON SCHEMA public TO gyeol_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO gyeol_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE gyeol IN SCHEMA public GRANT SELECT ON TABLES TO gyeol_ro;
```

- 마지막 줄은 이후 Flyway가 만드는 새 표에도 SELECT를 준다.
- 비밀번호가 대화 기록에 남지 않게 SQL은 사용자가 직접 실행한다(관리자 계정 만들기와 같은 원칙, 9절 12번).
- 데이터를 고쳐야 할 때는 맥북에서 직접 고치지 않는다. Flyway 마이그레이션이나 관리자 API로 한다.

## 15. 요청 수 제한 (Traefik RateLimit + 앱 IpRateLimiter)

ALB·AWS WAF는 두지 않는다. 서버가 1대라 ALB가 할 일이 없고, WAF는 탄력적 IP·EC2에 직접 붙일 수 없어 ALB나 CloudFront를 같이 들여야 한다(**추정**: AWS 문서 기억, 비용은 다시 조회 필요). Cloudflare 프록시는 결정 S4대로 끈다. 대신 무료인 두 겹으로 봇과 반복 요청을 줄인다.

| 겹 | 위치 | 막는 것 | 상태 |
|---|---|---|---|
| 1 | Traefik `RateLimit` 미들웨어 | IP 하나가 `/api` 전체를 짧은 시간에 많이 부르는 것 | 새로 (이 절) |
| 2 | 앱 `IpRateLimiter`(`room/RoomSupport.kt`) | 경로별 분당 횟수: 방 참가·다시 참가·초안·호스트 초기화·관리자 로그인 | 있음 (**사실**) |

### 15-1. Traefik 미들웨어

```yaml
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: gyeol-api-ratelimit
spec:
  rateLimit:
    average: 20     # IP당 초당 평균 20건
    period: 1s
    burst: 100      # 순간 100건까지
```

- 붙이는 곳: 기본 Ingress의 `/api` 경로만. Helm에서 `/api`를 별도 Ingress로 나누고 주석 `traefik.ingress.kubernetes.io/router.middlewares: <네임스페이스>-gyeol-api-ratelimit@kubernetescrd`를 단다. 웹 정적 파일(`/`)은 가볍고 캐시되므로 제한하지 않는다.
- 값 근거: 지도를 열면 `map` 1건 + 화면 안 가수 사진(`/api/rooms/{rid}/artists/{id}/image`)이 한꺼번에 나간다. 새벽 우주(행성 37) 휴대폰 맞춤에서 이미지 36장을 받았다(**사실**, I-44 웹 실측). 그래서 순간 허용(burst)은 100으로 넉넉히, 평균은 20으로 둔다. 실제 운영 로그를 보고 조정한다.
- 기준 IP: 프록시를 끄므로 방문자가 서버에 직접 붙는다. Traefik 기본 기준(연결한 쪽 주소)을 그대로 쓴다. `ipStrategy`는 두지 않는다.
- 넘으면 Traefik이 429를 돌려준다. 웹은 사진 요청 실패를 1분 기억하고 앨범 표지·장르색으로 대신 보여 주므로(**사실**, I-44 `roomPhoto`) 화면이 깨지지 않는다.
- Helm 값: `rateLimit.enabled`(기본 false, `values-single.yaml`에서 true), `rateLimit.average`, `rateLimit.burst`. 로컬 k3d에서는 끈 채로 둔다.
- CRD 이름 `traefik.io/v1alpha1`은 k3s 번들 Traefik v3 기준이다(**확인 필요**, 8절 관리자 허용과 같이 확인).

### 15-2. 앱 쪽 보완 (구현 때 함께)

- 검색 2개(`/api/rooms/{rid}/search`, `/api/tracks/search`)는 외부 API(Last.fm·MusicBrainz)를 부르는데 앱 제한이 없다(**사실**, `limiter.check` 호출 위치 확인). `IpRateLimiter`로 분당 횟수를 건다(예: 분당 30). 외부 API 쪽 차단을 막는 목적이다.
- `X-Forwarded-For` 위조 확인: 앱은 이 헤더의 첫 값을 IP로 쓴다(**사실**, `RoomSupport.kt`·`AdminSecurity.kt`). 방문자가 이 헤더를 직접 넣어 보내면 앱 제한을 피할 수 있다. Traefik은 신뢰하지 않는 곳에서 온 `X-Forwarded-*`를 지우고 자기 값으로 바꾼다고 알고 있다(**추정**). 첫 배포 뒤 가짜 헤더를 넣은 요청으로 앱이 보는 IP를 확인하고, 안 지워지면 Traefik `forwardedHeaders` 설정이나 앱의 IP 읽는 방법을 고친다.

### 15-3. 더 필요해지면

| 순서 | 방법 | 비용 | 언제 |
|---|---|---|---|
| 1 | Cloudflare 프록시 켜기 (S4 다시 결정) | 무료 | 실제 공격이나 봇 트래픽이 로그에 보일 때. 5-1절의 바꿀 것 4가지를 같이 한다 |
| 2 | ALB + AWS WAF | 월 수십 달러 (**확인 필요**) | 서버를 여러 대로 늘리거나 EKS로 옮길 때 |
