# 가상 Takeout 생성기

실제 "내 활동 → YouTube · JSON"(`내활동.json`)과 **같은 구조·비율**의 가짜 기록을 만든다. 곡·가수·채널 이름은 모두 가상(`가상가수017`, `Fake Song 0421` 등)이고, 실제 개인 기록은 저장소에 넣지 않는다.

```bash
# 실제와 같은 규모 (약 44MB, 결과 폴더는 git 제외)
node tools/fake-takeout/generate.mjs --watches 95681 --out tools/results/myactivity-real-scale.json

# 테스트용 작은 파일 (저장소에 커밋, 시드 고정이라 항상 같은 내용)
node tools/fake-takeout/generate.mjs --watches 300 --seed 1 --out tools/fixtures/myactivity-small.json
node tools/fake-takeout/generate.mjs --watches 300 --seed 1 --kind youtube --lang en --out tools/fixtures/watch-history-en-small.json
```

| 옵션 | 기본값 | 뜻 |
|---|---|---|
| `--watches` | 50000 | 시청 항목 수. 검색·기타 활동은 실제 비율로 따라 붙음 |
| `--start`, `--end` | 2023-03-11, 2026-10-24 | 기간 |
| `--seed` | 42 | 같은 시드면 같은 파일 |
| `--kind` | myactivity | `youtube`면 "YouTube 및 YouTube Music → 시청 기록" 형식(검색 없음) |
| `--lang` | ko | `en`은 공개 자료 기준 추정 문구 |

## 실제 파일과 비교 (2026-10-24, 시청 95,681건 기준)

| 항목 | 실제 | 가상 |
|---|---|---|
| 시청 / 검색 / 기타 | 95,681 / 8,639 / 6,705 | 같음 |
| 뮤직 앱 비율 | 21.8% | 21.7% |
| 규칙 필터 음악 | 25,409 (26.6%) | 25,362 (26.5%) |
| 고유 곡 / 곡당 재생 | 4,628 / 5.5회 | 4,467 / 5.7회 |
| 규칙 밖 고유 영상 | 52,494 | 53,550 |
| 채널 없음(삭제 영상) | 4,305 | 약 4,200 |
| 연도별 | 1,534 / 9,982 / 46,911 / 37,254 | 1,561 / 10,094 / 46,862 / 37,164 |

- 실측이 아닌 가정: 일반 앱 시청 중 뮤직비디오·커버 비율 15% (L2의 YouTube API 판정 대상). L2에서 실제 비율을 재면 고친다.
- 실제와 같은 고정 문구: `… 을(를) 시청했습니다.`(앞 공백), `… 을(를) 검색했습니다.`, 삭제 영상은 제목이 주소이고 채널 없음, 최신순 정렬, 대부분 밀리초 포함 시각.
