# 가수 화면 설계 (I-30, 2026-11-14 초안)

상태: 설계만. 코드는 승인 뒤.

## 0. 문제와 요약

문제 (**사실**, 11-14 사용자 화면 확인):
1. 방 검색은 10곡 고정이다(`RoomSongService.search` → `TrackSearchController.search(q, 10)`, 페이지 없음). "다비치"처럼 가수 이름만 치면 그 가수 노래를 더 볼 방법이 없다.
2. 같은 가수가 두 이름으로 나온다(성시경/Sung Si Kyung, 다비치/DAVICHI).
3. 같은 곡이 다른 표기로 겹쳐 나온다("너의 모든 순간"/"Every Moment Of You").

제안 (4단계):

| 단계 | 내용 | DB 변경 | 외부 호출 |
|---|---|---|---|
| ① | 가수 묶기·같은 곡 합치기 + 검색어가 가수와 맞으면 결과 맨 위에 **가수 카드** 1개("노래 전체 보기") | 없음 | 없음(DB) |
| ② | 가수 화면 **인기순**: Last.fm 인기곡, 이어 불러오기, 담음·친구 표시 | 없음 | Last.fm(빠름) |
| ③ | 가수 화면 **최신순**: MusicBrainz "처음 나온 날"을 가수별로 뒤에서 모아 저장 | **V27** | MusicBrainz(초당 1회) |
| ④ | 지도 행성 → 같은 가수 화면 | 없음 | 없음 |

LLM은 쓰지 않는다(11절).

## 1. 표본 조사 (11-14, 로컬 DB·fetcher, 외부 호출은 fetcher 속도 제한 안에서)

### 1-1. 로컬 DB (**사실**)

| 항목 | 값 |
|---|---|
| 식별된 곡 | 321곡 |
| 그중 `release_date` 있음 | 318곡(99%), 연도만 3곡 |
| 나라별 발매일 채움 | KR 204/204, JP 29/31, US 16/16, 나라 모름 69/70 |
| 가수 | 1,494명, 그중 별칭(`aliases`) 있음 1,223명(82%) |
| 예 | `성시경` 별칭 Sung Si Kyung / Sung Si-Kyung, `DAVICHI` 별칭 다비치 / Davichi |

- 식별된 곡은 처음 실린 공식 음반 날짜가 거의 다 있다. 다만 이것은 **우리가 식별한 곡**이다. 가수의 전체 곡이 아니다.
- 같은 곡 다른 표기의 예:
  - DB의 곡 제목은 MusicBrainz 원문 "너의 모든 순간 (Every Moment of You)" 하나다(곡 id 131).
  - `title_alias`에는 YouTube 제목 "너의 모든 순간", "Every moment of you (너의 모든 순간)"이 이 곡으로 연결돼 있다.
  - 검색 결과의 제목은 `title_alias.query_title`의 최빈값을 보여 준다.
  - Last.fm 결과("Every Moment Of You")와는 정규화한 제목이 달라서, 지금 규칙(`TrackSearchController.key`)으로는 합쳐지지 않는다.

### 1-2. Last.fm 인기곡 (fetcher `/internal/lastfm/top-tracks`, limit 20, 15명)

| 가수 (검색 이름) | 인기곡 수 | 앞 3곡 | 비고 |
|---|---|---|---|
| 다비치 | 20 | 8282, My Man, 시간아 멈춰라 | |
| DAVICHI / Davichi | 20 | This love, Sunset, Forgetting You | **"다비치"와 목록이 다름**(Last.fm 가수 페이지가 따로) |
| 성시경 | 20 | 사랑하는 일, 거리에서, one more part | |
| Sung Si Kyung | 20 | (성시경과 같음) | Last.fm autocorrect가 "성시경"으로 바꿔 줌 |
| 아이유, WOODZ, Clairo, 검정치마, 윤딴딴, 새소년, 카더가든, 정밀아 | 각 20 | | 새소년 "난춘"·"NAN CHUN 난춘"이 함께 있음(같은 곡 다른 표기) |
| 모트, 우효, 이랑, 김사월, 조월 (덜 알려진 인디) | 각 20 | | |

- 15명 모두 20곡을 채웠다(**사실**). 지금 fetcher가 `limit` 최대 20이라 그 너머는 재지 못했다.
- 덜 알려진 가수의 실제 인기곡 양(50·100곡까지 있는지)은 **확인 필요**: fetcher에 `page`·`limit 50`을 더한 뒤 다시 잰다(②의 첫 일).
- 같은 가수의 한글·영문 페이지가 서로 다른 인기곡을 줄 수 있다(다비치). 한 이름만 부르면 빠지는 곡이 생긴다.

### 1-3. MusicBrainz 발매 연도 (fetcher `/internal/musicbrainz/search`, 4명 × 인기곡 10곡 = 40곡, 45초)

| 가수 | 제목이 정확히 맞고 연도 있음 | 맞지만 연도 없음 | 검색 상위 5개에 정확한 제목 없음 |
|---|---|---|---|
| 우효 | 5 | 0 | 5 |
| 다비치 | 5 | 1 | 4 |
| 조월 | 6 | 0 | 4 |
| Clairo | 6 | 0 | 4 |
| 합계 | **22/40 (55%)** | 1 | 17 |

- 이 수치는 **하한**이다. 자유 문장 검색("가수 제목")은 순위가 흔들려 맞는 곡을 놓친다.
  - 앞서 같은 "다비치 8282"는 DAVICHI의 8282를 1위로 돌려줬는데, 이번 표본에서는 상위 5개에 없었다.
  - "민들레 - single ver."처럼 Last.fm 제목에 붙은 판 표시도 원인이다.
- 정확한 방법은 **가수 MBID로 녹음 목록을 훑기**(`/ws/2/recording?artist=<mbid>&limit=100&fmt=json`, 녹음마다 `first-release-date`)다(**추정**: MusicBrainz API 문서로 필드 확인 필요). 이 경로는 fetcher에 아직 없어서 표본을 재지 못했다. ③의 첫 일로 잰다.
- 속도: 40건에 45초 = 건당 약 1.1초(fetcher 속도 제한, **사실**).

### 1-4. MusicBrainz 가수 이름 찾기

- `/internal/musicbrainz/artist?name=다비치`는 `found:false`였다(점수 95 이상만 받는 규칙, **사실**).
- 한글 이름으로 MusicBrainz 가수를 찾는 것은 믿기 어렵다. 가수 카드는 **우리 DB의 가수(별칭 포함)를 먼저** 쓴다(①).

## 2. ① 가수 묶기·같은 곡 합치기·가수 카드

### 2-1. 가수 묶기

- 기준은 **MusicBrainz 가수 ID**(`artist.mbid`, UNIQUE)다. 한 MBID = 한 가수. 이름·별칭(`artist.aliases`)은 같은 가수의 다른 표기다(**사실**, V4).
- DB 결과는 이미 가수 하나로 묶여 있다. 겹침은 **Last.fm 결과**에서 생긴다. Last.fm 결과의 가수 이름을 우리 가수로 바꿔 쓴다:
  1. Last.fm 결과 가수 이름(" • 잡음" 정리 뒤)을 정규화(`SearchText.norm`)
  2. `artist.name` 또는 `aliases` 중 정규화 값이 같은 가수가 **한 명뿐**이면 그 가수로 본다. 표시 이름은 우리 표시 규칙(DB 쪽 최빈 표기).
  3. 여러 명이면(동명이인) 바꾸지 않는다.
- 새 표나 V27은 필요 없다. `artist` 별칭 조회용 색인은 구현 때 실행 계획을 보고 정한다(`aliases` GIN 또는 정규화 이름 표). 가수 수 1,494명이면 지금은 필요 없을 것으로 본다(**추정**).

### 2-2. 같은 곡 합치기

순서대로 맞으면 같은 곡으로 보고 한 줄만 남긴다(DB 쪽 우선):
1. Last.fm `mbid`(녹음 MBID)가 DB `track.recording_mbid`와 같음
2. `title_alias`에 "정규화 제목|정규화 가수(Topic 채널 키)"가 MATCHED로 있음. 식별 캐시를 그대로 쓴다.
3. **괄호 양쪽 표기**: MusicBrainz 제목 "A (B)"는 A와 B를 모두 그 곡의 제목 표기로 본다(예: "너의 모든 순간 (Every Moment of You)").
   - Last.fm 제목의 정규화 값이 둘 중 하나와 같고 가수가 같으면 같은 곡.
   - `track_search_term`의 TITLE 항목에 A·B가 이미 들어가는지는 구현 때 확인한다. 없으면 이 규칙으로 넣는다.
4. 판 표시 제거 뒤 비교: "- single ver.", "(Inst.)", "(Live)", "Remix" 같은 꼬리는 **합치지 않는다**. 다른 녹음이다. 단 Last.fm의 "NAN CHUN 난춘"처럼 같은 제목을 두 언어로 붙인 경우는 규칙 3과 같은 방식(공백으로 나뉜 양쪽 중 하나가 같음)으로 본다(**추정**, 표본 몇 개로 확인).

- 남는 한계: DB에 없는 곡의 영문·한글 표기(둘 다 Last.fm 결과)는 근거가 없으면 합치지 않는다. 틀리게 합치는 것보다 두 줄이 낫다.

### 2-3. 가수 카드

- 조건: 검색어의 정규화 값이 어떤 가수의 이름·별칭과 **같거나**(score 4) 그 가수 곡이 결과의 절반 이상이고 검색어가 이름의 앞부분(score 3).
- 카드 하나만(가장 많이 들린 가수, `plays_total` 합).
- DB에 가수가 없으면: Last.fm `artist.getInfo`(autocorrect) 이름이 검색어와 같을 때만 카드를 만든다(`artistId` 없음).
  - 누르면 가수 화면이 이름으로 열리고, 가수가 DB에 생기는 것은 그 가수 곡을 처음 담아 식별될 때다.
- API: 기존 `GET /api/rooms/{rid}/search?q=` 응답에 `artist` 필드 추가(없으면 null). 기존 필드는 그대로.

```json
"artist": { "artistId": 42, "name": "다비치", "aliases": ["DAVICHI"], "songCountInRoom": 3, "onMap": true }
```

## 3. ② 가수 화면: 인기순

### API

`GET /api/rooms/{rid}/artists/{artistId}/songs?sort=popular&page=1`
- DB에 없는 가수는 `.../artist-songs?name=다비치&sort=popular&page=1` (artistId 없는 카드용)
- 기존 `GET /api/rooms/{rid}/artists/{artistId}`(행성 자세히)는 그대로다. 경로가 겹치지 않는다(**사실**).

응답:
```json
{ "artist": {...}, "sort": "popular", "page": 1, "pageSize": 20, "hasMore": true,
  "items": [ { "item": TrackItem, "mine": false, "sameSong": ["민지"], "rank": 1 } ] }
```
- `item`·`mine`·`sameSong`은 방 검색(`RoomSearchItem`)과 같은 모양이다. 웹이 담기·금빛 표시를 그대로 쓴다.
- 페이지: 20곡씩 `page` 번호. 끝에서 이어 불러오기(무한 스크롤). 최대 5페이지(100곡)까지(결정 A6).

### 만드는 방법

1. fetcher `/internal/lastfm/top-tracks`에 `page` 추가, `limit` 최대 50(**코드 변경**).
   - Last.fm `artist.getTopTracks`는 `page`·`limit`을 받는다(**추정**, API 문서 확인 필요).
2. 가수의 Last.fm 이름 후보 = 표시 이름 + 별칭 중 한글 하나·로마자 하나(최대 3개). 다비치처럼 페이지가 갈린 경우를 합친다(결정 A2).
3. 후보마다 같은 페이지를 받아 같은 곡 합치기(2-2)를 한다. 순서는 Last.fm `playcount` 합으로 정한다. 응답에 들어 있다(**추정**, 지금 fetcher는 제목·가수만 넘김 → `playcount`·`listeners`·`mbid`도 넘기게).
4. 각 곡을 DB 곡과 맞춰(2-2) `trackId`·표지를 붙인다. 없으면 Last.fm 곡(담으면 식별 요청 202, 지금과 같음).
5. `cleanRemote`(" • 잡음" 정리, I-29) 적용.
6. 방 표시: `mine`(내가 담음 → "담음"), `sameSong`(친구가 담은 곡 → 금빛). `songs.whoHasTracks`를 다시 쓴다(**사실**, 기존 함수).

- 캐시: fetcher의 Last.fm 캐시(있는 것)를 쓴다. 같은 가수 화면을 여러 사람이 열어도 Last.fm 호출이 늘지 않게(**확인 필요**: fetcher 캐시 TTL).
- 속도: Last.fm 호출 1~3번(병렬), 각 수백 ms(**추정**). 화면 첫 페이지 1초 안쪽 목표.

## 4. ③ 가수 화면: 최신순 (V27)

### 4-1. 데이터 (전역, 사용자와 연결 없음 — 기존 `artist`·`track`과 같은 성격)

```sql
-- V27__artist_releases.sql (예정)
CREATE TABLE artist_release_scan (
    artist_id   BIGINT PRIMARY KEY REFERENCES artist (id) ON DELETE CASCADE,
    status      VARCHAR(10) NOT NULL CHECK (status IN ('QUEUED','RUNNING','DONE','FAILED')),
    recordings  INT,           -- 훑은 녹음 수
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);
CREATE TABLE artist_recording (
    artist_id          BIGINT NOT NULL REFERENCES artist (id) ON DELETE CASCADE,
    recording_mbid     UUID   NOT NULL,
    title              TEXT   NOT NULL,
    title_norm         TEXT   NOT NULL,   -- 같은 곡 합치기용
    first_release_date VARCHAR(10),       -- YYYY, YYYY-MM, YYYY-MM-DD (없으면 NULL)
    is_version         BOOLEAN NOT NULL DEFAULT false, -- Live·Inst.·Remix 등 판 표시
    PRIMARY KEY (artist_id, recording_mbid)
);
CREATE INDEX idx_artist_recording_date ON artist_recording (artist_id, first_release_date DESC NULLS LAST);
```

- `track` 표에 직접 넣지 않는다. `track`은 "식별된 곡"이라 검색어·지도·추천이 붙는다. 녹음 목록 수천 개를 넣으면 검색이 흐려진다(결정 A4).
- 담을 때는 녹음 MBID를 넘겨 기존 식별 흐름(202)을 탄다(`AddSongRequest.mbid`, **사실**).

### 4-2. 큐·worker

- 메시지: 기존 `room-song` 큐에 새 종류 `SCAN_ARTIST_RELEASES {artistId}`를 더한다(결정 A5: 새 큐면 Terraform·Helm·init-aws.sh도 바꿔야 함).
- 요청 시점: 가수 화면을 최신순으로 처음 열 때 API가 `artist_release_scan`이 없거나 90일 지났으면 QUEUED로 넣고 보낸다. 같은 가수는 한 번만.
- worker: fetcher 새 경로 `/internal/musicbrainz/artist-recordings?mbid=&offset=` → 100개씩 → 같은 제목은 가장 이른 날짜 하나로 → `artist_recording`에 저장 → DONE.
- 시간 추정(**추정**, 초당 1회 제한, 요청당 약 1.1초 실측 기준):

| 가수 녹음 수 (MusicBrainz, 라이브·리믹스 포함) | 요청 | 걸리는 시간 |
|---|---|---|
| 100 | 1 | 약 1초 |
| 500 | 5 | 약 6초 |
| 2,000 (오래 활동한 대형 가수) | 20 | 약 22초 |

  - 가수별 실제 녹음 수는 **확인 필요**(③ 첫 일로 5명 표본).
  - fetcher의 MusicBrainz 제한은 식별·지도와 함께 쓴다. 여러 가수가 몰리면 대기열이 길어진다. 동시에 1가수만 처리한다.

### 4-3. API

`GET /api/rooms/{rid}/artists/{artistId}/songs?sort=latest&page=1`
- 스캔 상태 `scan: "RUNNING" | "DONE" | "FAILED"`.
  - RUNNING이면 그때까지 저장된 것만, 웹은 "발매일을 모으는 중"을 보여 주고 몇 초 뒤 다시 읽는다.
- 정렬: `first_release_date` 내림차순, 날짜 없는 곡은 맨 뒤 `releaseDate: null` → 웹 "발매일 모름".
- 판 표시 곡(`is_version`)은 기본으로 숨긴다(결정 A7).
- 페이지 20곡씩 offset.
- DB에 없는 가수(artistId 없음)는 최신순을 열 수 없다. MBID를 모르기 때문이다. 웹은 인기순만 보여 준다.

## 5. ④ 지도 행성 → 가수 화면

- 행성 자세히(`GET /api/rooms/{rid}/artists/{artistId}`)는 이미 `artistId`를 쓴다(**사실**). 웹에서 "노래 전체 보기"를 누르면 ②·③ 화면으로 간다.
- 백엔드 변경 없음. 웹 작업만.

## 6. 기존 식별·검색과의 관계

| 기존 | 이번에 하는 일 |
|---|---|
| `artist`(mbid·aliases) | 가수 묶기 기준으로 그대로 사용 |
| `track`·`track_search_term` | 그대로. 가수 카드 판정에 ARTIST 항목을 읽기만 |
| `title_alias`(제목\|채널 → 곡) | 같은 곡 합치기 2번 규칙에서 읽기만 |
| `TrackSearchController`(DB + Last.fm, 10곡) | 결과 정리(2-1·2-2) 추가, 가수 카드 판정 추가. 10곡 제한은 그대로 |
| 식별(`IdentifyService`) | 가수 화면에서 담기도 지금처럼 식별 요청(202) |
| 지도(`artist_position`) | 가수 카드 `onMap` 표시에만 사용 |

## 7. 웹 (갈래 A 몫, 참고)

- 검색 결과 맨 위 가수 카드 → 가수 화면.
- 탭: 인기순 / 최신순.
- 곡 줄: 표지·제목·(최신순) 발매일·담음·친구 금빛.
- 끝에서 이어 불러오기. 최신순 수집 중 안내.

## 8. 테스트 계획

- 단위:
  - 가수 이름 바꾸기(별칭 하나만 맞을 때만, 동명이인은 그대로)
  - 같은 곡 합치기 규칙 1~4
  - 괄호 양쪽 표기
  - 판 표시 판정
- api 통합(가짜 fetcher):
  - "다비치" 검색 → 가수 카드 1개
  - Last.fm 결과 "DAVICHI"·"다비치"가 한 가수로
  - "Every Moment Of You"가 DB 곡과 한 줄로
  - 인기순 2페이지(hasMore)
  - 담음·친구 표시
  - 최신순: 스캔 요청 1번만, RUNNING 응답, 날짜 없는 곡 맨 뒤, 판 표시 숨김
- worker 통합(가짜 fetcher):
  - 녹음 250개(3페이지) 저장
  - 같은 제목 가장 이른 날짜
  - 실패 → FAILED
  - 다시 요청 90일 규칙
- fetcher: `top-tracks` page·limit 50, `artist-recordings` 페이지 넘김(가짜 MusicBrainz 서버).

## 9. 비용

- LLM: **없음**. 이름·제목 비교는 규칙으로 한다.
- Last.fm·MusicBrainz: 무료(키·속도 제한 안).
- DB: 가수 1명당 녹음 수백~수천 행. 전역 데이터라 방이 지워져도 남는다(기존 `track`과 같음).

## 10. 확인 필요

- Last.fm `artist.getTopTracks`의 `page` 지원과 가수별 실제 곡 수(1-2, 20곡 너머)
- `artist.getTopTracks`를 `mbid`로 부를 수 있는지, 한국 가수 페이지에 MBID가 붙어 있는지(되면 이름 후보 대신 MBID로)
- MusicBrainz 녹음 목록(`recording?artist=`)의 `first-release-date` 필드와 한국 곡 채움 정도(1-3은 검색 기반 하한 55%)
- 가수별 녹음 수(4-2 시간 추정)
- fetcher Last.fm 캐시 TTL

## 11. 결정 (2026-11-14 사용자: A1~A9 추천안, A8만 7일로 변경)

| # | 결정 | 추천 | 다른 선택 |
|---|---|---|---|
| A1 | 가수 카드 출처 | **우리 DB 가수(이름·별칭) 먼저**, 없으면 Last.fm autocorrect 이름이 같을 때만(artistId 없음) | MusicBrainz 이름 찾기(한글 이름 실패, 1-4) |
| A2 | 인기순에 부를 Last.fm 이름 | **표시 이름 + 별칭 최대 2개(한글·로마자) 합치기** | 한 이름만(다비치처럼 곡이 빠짐) |
| A3 | 같은 곡 합치기 | **2-2 규칙 1~3(+4는 양쪽 표기만)**, 근거 없으면 두 줄로 둠 | 제목 유사도로 적극 합치기(틀린 합치기 위험) |
| A4 | 발매일 저장 | **새 전역 표 `artist_recording`(V27)** | `track`에 넣기(검색·지도에 섞임) |
| A5 | 수집 큐 | **기존 room-song 큐에 새 메시지 종류** | 새 큐(Terraform·Helm·init-aws.sh 변경) |
| A6 | 인기순 최대 | **100곡(20곡 × 5페이지)** | 50곡, 제한 없음 |
| A7 | 최신순 판 표시(Live·Inst.·Remix) | **기본 숨김**(같은 제목은 가장 이른 날짜 하나) | 모두 보이기 |
| A8 | 최신순 다시 모으기 | **7일 뒤 처음 열 때**(사용자 결정, 추천은 90일이었음). 설정값 `ARTIST_SCAN_REFRESH`(기본 7d)로 빼서 운영 중 조정. 근거: 신곡 반영과 MusicBrainz 부담(초당 1회를 식별과 공유)의 균형, MusicBrainz 입력 지연 때문에 1일은 효과가 적음 | 1일 / 15일 / 90일 |
| A9 | 구현 순서 | **① → ② → ④ → ③**(③만 V27·worker라 마지막) | 설계 순서대로 |
