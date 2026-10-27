# 테스트 방법

## 1. 자동 테스트 (한 번에)

```bash
scripts/test-all.sh
```

- 백엔드 단위 + 통합(Testcontainers가 PostgreSQL·LocalStack을 따로 띄움, **Docker 필요**) + 웹(Vitest). 끝에 모듈별 개수를 요약한다. 약 1분 30초.
- 실행 중인 로컬 서버(`scripts/dev-up.sh`)와 겹치지 않는다. 테스트는 자기 컨테이너를 쓴다.

| 모듈 | 개수 | 내용 |
|---|---|---|
| api | 9 | 업로드 주소 → S3 → 통계 → 삭제, 곡 저장, 400·404·409·413, 만료 세션 404, 통계 한국 시간 경계, 곡 검색·503, 식별 진행률, 곡·가수 단위 순위(합치기·표시 이름·표지), 고른 곡 식별 요청, 우리 DB 검색(붙여쓰기·부분·초성·오타·별칭·가로채기 방지·Last.fm 장애·바로 연결), 일반 영상 음악 판정(캐시·장애 시 건너뜀·상태·형식) |
| worker | 33 | parse 통합 5(저장·중복 메시지·형식 오류·S3 시험 메시지·2.5만 건), 업로드 파일 검증 단위 5, 만료 데이터 정리 통합 5, 곡 식별 통합 3(계획·전역 캐시·표지·실패 큐), 규칙·정규화 키 단위 4, 검색 문자열 단위 3, LLM 검증·가짜 정리기 단위 5, LLM 식별 통합 1, 판정 캐시 정리 1, 지식 인입 통합 1(가수·작품 한 번만, 공유 작품 크레딧, 90일 캐시) |
| external-fetcher | 37 | 속도 제한 3, MusicBrainz 5, Last.fm 5, 다시 정렬 7, 곡 식별 규칙 8, 표지 규칙 4, 지식 청크 5 |
| web | 7 | Takeout 거르기 (한국어·영어, 삭제 영상, 일반 영상, 검색 기록 파일 거절, 형식 오류, 미분류는 브라우저에 두고 상위 영상 ID만) |

모듈 하나만: `./gradlew :backend:worker:test`, `cd web && npm test`

## 2. 브라우저 전체 흐름 (수동 점검표)

L1 완료 기준(`docs/l1-design.md` 9장) 1~3번을 확인한다. 바뀐 화면이나 API가 있으면 다시 한다.

**준비**
```bash
scripts/dev-up.sh
node tools/fake-takeout/generate.mjs --watches 95681 --out tools/results/myactivity-real-scale.json   # 실제 규모 가상 파일 (약 50MB, git 제외)
```

**A. 곡 고르기**
- [ ] http://localhost:15173 → "곡 3~5개 고르기"
- [ ] "다비치 8282", "태연 사계", "윤하 사건의 지평선"을 검색해 하나씩 고른다 (입력을 멈추면 0.5초 뒤 검색)
- [ ] 2곡일 때 버튼이 "1곡 더 골라 주세요", 5곡이면 더 못 고름
- [ ] "이 곡들로 시작하기" → `/r/…`에 고른 곡이 순서대로 보임

**B. 청취 기록 올리기**
- [ ] "청취 기록 올리기" → "JSON 파일 고르기"에서 위 가상 파일 선택
- [ ] 미리보기: 시청 95,681 / 보낼 음악 약 25,000 / 판정 못 함, 기간, 보낼 크기 약 3.6MB, 거르는 시간 약 1초
- [ ] 올리기 → 결과 화면에 "…동안 음악을 …번 들었어요", 순위, 그래프 3개, 미분류 안내
- [ ] 검색 기록만 있는 파일, JSON이 아닌 파일을 고르면 안내 문구가 나옴

**C. 삭제와 화면**
- [ ] 결과 화면 "지금 삭제" → 첫 화면으로 이동, 같은 링크를 열면 "결과를 찾을 수 없어요"
- [ ] S3에서도 사라졌는지: `docker exec gyeol-local-localstack-1 awslocal s3 ls s3://gyeol-local-uploads/uploads/`
- [ ] 라이트·다크 모드, 휴대폰 너비(375px)에서 글자·그래프가 잘리지 않음

**파일 선택 창 없이 넣기** (자동화 도구로 점검할 때): 가상 파일을 잠시 `web/public/_e2e.json`에 복사하고, 개발 도구 콘솔에서
```js
const t = await (await fetch('/_e2e.json')).text()
const input = document.querySelector('input[type=file]')
const dt = new DataTransfer(); dt.items.add(new File([t], '내활동.json', { type: 'application/json' }))
input.files = dt.files; input.dispatchEvent(new Event('change', { bubbles: true }))
```
끝나면 `web/public/_e2e.json`을 반드시 지운다.

## 곡 식별 정확도 (실제 MusicBrainz)

```bash
( set -a; . ./.env; set +a; java -jar backend/external-fetcher/build/libs/external-fetcher-0.1.0.jar ) &   # 또는 scripts/dev-up.sh
node tools/identify-eval/eval.mjs     # 37곡 표본, 약 3분
```

## 곡 검색 정확도

```bash
node tools/search-eval/eval.mjs                     # L1 질의 표본 33개 (k3d 또는 dev 서버의 api)
node tools/search-eval/eval.mjs --samples backend/external-fetcher/src/test/resources/lastfm-zard-samples.json
```

## 지식 인입 채움률

```bash
python3 tools/knowledge-eval/measure.py   # dev 서버가 떠 있어야 한다. DB의 식별된 곡 전부, 무료 API, 몇 분
```

## 3. 만료 데이터 정리 확인

worker가 매일 04:00(한국 시간)에 돈다(`CLEANUP_ENABLED`, `CLEANUP_CRON`). 로컬에서 바로 보려면 `CLEANUP_CRON='0 * * * * *'`(매분)으로 worker를 띄우고 `.run/worker.log`에서 "정리 완료"를 확인한다.

## 4. k3d (로컬 쿠버네티스)

```bash
scripts/k3d-up.sh        # 클러스터 생성(없으면) → 빌드 → 이미지 → helm 배포. 접속 http://localhost:18000
scripts/k3d-down.sh      # 클러스터 삭제 (DB·LocalStack은 compose라 남음)
kubectl -n gyeol get pods
```

- DB·LocalStack은 클러스터 밖 compose를 쓴다. `scripts/dev-up.sh`의 Java 서버와 함께 띄우면 메모리가 빠듯하니 `KEEP_DOCKER=1 scripts/dev-down.sh`로 Java·웹만 끄고 쓴다.
- 위 2절 점검표를 `http://localhost:18000`에서 그대로 한다.
- 파드 복구: `kubectl -n gyeol delete pod -l app.kubernetes.io/name=gyeol-api` 후 다시 응답할 때까지 시간을 잰다.

**2026-10-26 측정 (맥북, Docker CPU 8·메모리 7.7GB)**

| 항목 | 값 |
|---|---|
| 클러스터 새로 만들고 배포 완료까지 (빌드 생략) | 91초 (클러스터 생성 약 70초) |
| 이미지 4개 빌드 (jar 준비된 상태) | 약 22초 |
| B 흐름: 올리기 클릭 → 결과 화면 (음악 25,362건) | 7.1초 |
| api 파드 1개 삭제 → 다시 응답 (replica 1) | 17초 끊김 |
