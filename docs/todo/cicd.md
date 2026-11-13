# CI/CD 설계 (2026-11-14, 결정됨)

결정(사용자, 11-14): 이미지는 **GHCR 공개 이미지**, 배포는 **`v*` 태그 push 때만**, EKS 회차는 **Argo CD 없이 수동 실행 버튼**.
참고: `job-scheduler`의 `.github/workflows/{ci,checks,release}.yml`(태그 → 검사 → 빌드 → 릴리스 노트, OIDC). 배포 순서는 `deploy-decisions.md` D1.

## 1. CI — PR과 main push마다 (`.github/workflows/ci.yml` + 재사용 `checks.yml`)

| 작업 | 내용 | 실행 조건 (바뀐 경로) |
|---|---|---|
| backend | JDK 21, `./gradlew test` (Testcontainers: 러너의 Docker), 실패 시 테스트 리포트 업로드 | `backend/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/**` |
| web | Node 22, `npm ci` → `tsc --noEmit` → `vitest run` → `vite build` | `web/**` |
| embed | uv, Python 3.13, `pytest` (Hugging Face 모델은 actions/cache) | `embed/**` |
| infra | `terraform fmt -check`·`init -backend=false`·`validate` (base·bootstrap), `helm lint`·`helm template`(기본·local·single) | `infra/**`, `deploy/**` |
| scripts | `bash -n` (local/localstack/init-aws.sh, scripts/*.sh) | `local/**`, `scripts/**` |

- 공개 저장소라 GitHub 기본 러너 비용 0.
- 경로 필터(예: `dorny/paths-filter`)로 바뀐 영역만 실행. main push는 전체 실행.
- 비밀값 없음: CI는 AWS·외부 API를 부르지 않는다(LLM은 fake, Last.fm·YouTube 키 없이 도는 테스트만).
- `concurrency`로 같은 브랜치의 이전 실행 취소.

## 2. CD — `v*` 태그 push (`.github/workflows/release.yml`)

```
v0.3.0 push → checks(1절 전체) → images → release → deploy
```

| 단계 | 내용 |
|---|---|
| images | 이미지 5개(api·worker·external-fetcher·web·embed)를 **linux/arm64**로 빌드(서버 t4g). 공개 저장소용 ARM 러너가 있으면 그 러너에서, 없으면 buildx + QEMU. `ghcr.io/sangwoo-joo/gyeol-<이름>:v0.3.0`(+`latest`) 공개 패키지. `GITHUB_TOKEN`(packages: write)만 사용 |
| release | GitHub Release + 릴리스 노트 초안(이전 태그 이후 커밋, job-scheduler 방식), "핵심 변경"은 사람이 채움 |
| deploy | GitHub OIDC → AWS 역할(`gyeol-deploy`) → SSM `send-command`로 서버에서 ① `pg_dump` → S3 백업 ② `helm upgrade --install … --set image.tag=v0.3.0` ③ `kubectl rollout status` ④ `https://ourgyeol.com/api/health` 확인. 실패하면 워크플로 실패(알림은 GitHub 메일) |
| rollback | `workflow_dispatch`(입력: 태그) → 같은 deploy 단계로 이전 태그 배포(또는 `helm rollback`) |

### 권한과 비밀
- **GitHub에 AWS 키를 두지 않는다.** 저장소 Variables에 `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `SERVER_INSTANCE_ID`만.
- `gyeol-deploy` 역할(Terraform, 단일 서버 스택): 신뢰 정책은 이 저장소의 `refs/tags/v*`만, 권한은 그 인스턴스에 `ssm:SendCommand`(AWS-RunShellScript)와 결과 조회만.
- 앱 비밀(`ADMIN_TOTP_KEY`, `LASTFM_API_KEY`, `YOUTUBE_API_KEY`, DB 암호)은 **SSM Parameter Store(SecureString)**. 서버가 자기 IAM 역할로 읽어 k8s Secret을 만든다. GitHub와 이미지에는 들어가지 않는다.
- Helm 값 `image.registry`를 `ghcr.io/sangwoo-joo` 방식으로(지금은 `gyeol` 로컬 이름). 이름 규칙은 구현 때 차트에 맞춘다.

### 마이그레이션
- Flyway는 api 시작 때 실행되고 앞으로만 간다. 그래서 배포 ①에서 백업을 먼저 하고, 마이그레이션이 든 릴리스는 릴리스 노트에 표시한다.

## 3. EKS 회차
- 같은 GHCR 이미지·Helm 차트. `workflow_dispatch`(입력: 태그, 클러스터 이름)로 OIDC → EKS 접근(회차 스택이 만든 역할) → `helm upgrade`. 회차가 끝나면 destroy.
- Argo CD는 넣지 않는다(회차에만 뜨는 클러스터라 설치·설정 비용이 이득보다 큼). GitOps를 보여 주고 싶으면 회차 하나에서만 시험.

## 4. 구현 순서
1. **CI** (지금, 비용 0) — 1절.
2. **이미지·릴리스** (태그만으로 동작, 서버 없이도 가능) — 2절 images·release.
3. **deploy·rollback** — 단일 서버 Terraform(`deploy-decisions.md` D1 3단계) 뒤.
4. EKS 수동 배포 — EKS 회차 스택 뒤.

## 5. 기록

| 날짜 | 내용 |
|---|---|
| 11-14 | 설계, 결정 3가지(GHCR 공개·태그 배포·Argo CD 없음). CI 구현을 갈래 1에 맡김 |
| 11-14 | CI 구현·첫 통과(`664bae7`~`ecd6581`, CI #3 전 작업 통과) |
| 11-14 | images·release 구현(`release.yml`): ubuntu-24.04-arm 러너로 arm64 5개 → `ghcr.io/sangwoo-joo/gyeol-<이름>:<태그>`(하이픈 없는 태그만 latest), 노트에 마이그레이션 표시, 하이픈 태그는 prerelease. Helm `values-single.yaml`이 GHCR 주소를 쓰고 k3d는 그대로. 운영 배포 때 `--set image.tag=v…` 필수(GHCR에 `dev` 없음). 아직 태그로 실행해 보지 않음 |
