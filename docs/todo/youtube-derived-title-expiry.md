# I-48 4-2 방안 A 설계 메모: 판정 경로로 들어온 제목을 29일 뒤 지우기 (11-14, 승인 전 구현 안 함)

## 결론
- 판정 경로로 들어온 행은 **서버가 이미 알아볼 수 있다**. 조건은 `listen_event.source='YOUTUBE' AND channel NOT LIKE '% - Topic'`이다. 그래서 web은 고치지 않는다.
- 전역 테이블 4곳(`title_alias`·`title_guess`·`medley_song`, 그리고 세션 범위인 `identify_target`)에 `category_checked_at`을 둔다. 값이 NULL이면 판정 없는 근거가 있는 행이다.
- 29일이 지나면 **지운다**. 다시 판정하지 않는다. `track_search_term`은 지운 별칭이 붙어 있던 곡만 색인을 다시 만든다.
- 곡·가수(`track`·`artist`)와 방 노래는 그대로 남는다. 바뀌는 것은 다음에 같은 영상이 올라올 때 MusicBrainz로 다시 식별한다는 점뿐이다.
- 작업량은 약 1~1.5일이다. 로컬에서 지금 대상이 되는 행은 별칭 65개, 추측 207개 정도다.

## 1. 판정 경로로 들어온 행을 어떻게 아는가
- 브라우저는 판정을 통과한 영상을 따로 알려 주지 않는다. 업로드 본문에서는 통과한 영상(`extra`)도 Topic 영상처럼 `source='YOUTUBE'`로 섞인다(`web/src/pages/Upload.tsx:45-48`).
- 하지만 필터 규칙상 구별된다(`web/src/takeout/filter.ts:86-94`).
  - `source='YOUTUBE'`이고 채널이 ` - Topic`으로 끝나지 않는 행은 **판정 통과분뿐이다**.
  - 음악 앱 기록은 `MUSIC_APP`이고, Topic 영상은 채널 이름으로 구별된다.
- 로컬 실측: 이 조건에 맞는 `listen_event` 11,786행이 모두 `video_id`를 가졌다. 그 `video_id`는 모두 `video_check`에 category 10으로 있었다(11,786/11,786). 조건이 정확하다.
- 판정 시각은 `listen_event.video_id`를 `video_check`에 이어 `max(video_check.checked_at)`로 얻는다.
- 판정 없이 들어오는 경로는 대상이 아니다. 음악 앱 기록, Topic 채널, 방 담기가 그렇다. 방 담기는 채널을 `"{가수} - Topic"`로 만든다(`RoomSongService.kt:73`).
- 위험: 나중에 web 필터가 바뀌면 이 조건이 깨진다. 그래서 `filter.ts`의 불변식("non-Topic YOUTUBE = 판정분")을 주석으로 남긴다. web 코드는 이 갈래에서 고치지 않는다.

## 2. 표시 방법 (V31)

| 테이블 | 추가 컬럼 | 쓰는 곳 |
|---|---|---|
| `identify_target` | `category_checked_at TIMESTAMPTZ NULL` | `playGroups()`에서 그룹마다 계산한다. 판정 행만 있으면 `max(vc.checked_at)`, 하나라도 판정 없는 행이 있으면 NULL |
| `title_alias` | `category_checked_at TIMESTAMPTZ NULL` + 부분 인덱스(`WHERE category_checked_at IS NOT NULL`) | `saveAlias()`가 대상의 값을 받아 쓴다 |
| `title_guess` | 같음 | 추측 저장 때 그 세션의 `listen_event`로 계산한다 |
| `medley_song` | 같음 | 메들리 저장 때 같은 방식으로 계산한다 |

- `track_search_term`에는 컬럼을 두지 않는다. 표기의 출처가 `title_alias`라서, 별칭을 지울 때 그 곡 색인을 다시 만들면 된다. 지금 `reindex()`는 더하기만 하므로, 그 곡의 색인을 지운 뒤 다시 만든다.
- **같은 행이 판정 없는 경로로도 들어올 때 (표시 지우기)**
  - 별칭: 계획 단계에서 캐시를 맞힌 경우(`IdentifyPlanner.kt:54`)와 새로 저장하는 경우 모두 대상 그룹의 값으로 갱신한다.
    - 대상이 판정 없는 그룹이면 `category_checked_at = NULL`로 한다. 한 번 NULL이 되면 다시 표시하지 않는다. 사용자 자신의 Takeout·음악 앱 근거라 API와 무관하기 때문이다.
    - 대상이 판정 그룹이고 기존 값이 NULL이 아니면 `greatest(기존, 새 값)`으로 한다. 다른 세션이 다시 판정받았다면 기한이 늘어난다.
    - 대상이 판정 그룹이고 기존 값이 NULL이면 NULL을 유지한다.
  - `title_guess`·`medley_song`: 추측 재사용 경로(`TitleGuessRepository.targets`가 최근 추측을 건너뛰는 경우)에서도 세션마다 같은 규칙으로 갱신하는 쿼리를 하나 둔다.
- 기한은 판정 시각이다. 저장 시각이 아니다. 세션 B가 `video_check` 캐시(최대 29일 전 판정)를 썼다면 그 이른 시각을 그대로 쓴다. 그래서 어떤 경로로도 판정 시각에서 29일을 넘지 않는다.

## 3. 29일 뒤 처리: 지운다 (추천)
- 기준은 `VideoCheckRepository.RETENTION`(29일) 하나다. 정리 작업(매일 04:00)에 단계 하나를 더한다.
  1. `title_alias`에서 `category_checked_at < now-29d`인 행을 지운다(MATCHED·NOT_FOUND 모두). 그 행들의 `track_id`를 모아 `track_search_term`을 다시 만든다(지운 뒤 `reindex`).
  2. `title_guess`·`medley_song`에서 기한이 지난 행을 지운다.
  3. `identify_target`: 세션 자체가 30일 만료라 정리가 하루 늦으면 31일이 될 수 있다. 그래서 `category_checked_at < now-29d`인 행을 먼저 지운다.
  - 순서: 별칭을 지운 뒤 색인을 다시 만든다. 여러 번 돌아도 결과가 같다.
- **참조하는 곳이 깨지는가 (코드 확인)**
  - 방 노래 `room_song.track_id` → `track`: 곡은 지우지 않으므로 그대로다. 방 담기 별칭은 Topic 키라 대상도 아니다.
  - 공용 지도·가수 화면: `track`·`artist`·`artist_map` 기준이다. 별칭을 쓰지 않으므로 영향이 없다.
  - 결과 화면(`StatsService`)
    - 별칭은 표시 이름의 최빈값(`mode() … title_alias`)이나 연주·커버 라벨에 붙는 보조 정보다. 모두 LEFT JOIN·coalesce이거나 `listen_event`(7일)와 묶여 있다.
    - 29일이면 그 세션의 `listen_event`는 이미 없다. 다른 세션에서는 표시 이름이 MusicBrainz 곡명으로 바뀌는 정도다. 오류가 나는 경로는 없다.
  - 검색(`TrackSearchRepository`): 그 YouTube 표기로 검색해도 더는 걸리지 않는다. 곡명과 가수 이름으로는 그대로 찾는다.
  - 추천(`RecommendRepository:84`)과 지식 설명 생성(`DescriptorRepository:22`): 별칭 표기를 입력 보조로만 쓴다. 지운 뒤에는 MusicBrainz 이름으로 대체된다.
- **다시 판정하는 대안(추천하지 않음)**
  - 별칭마다 `video_id`를 새로 저장해야 한다. API 데이터와 사용자 데이터를 더 길게 잇는 셈이다.
  - 사용자 요청 없이 API를 부르게 된다.
  - 다시 판정해도 제목은 Takeout 값이라 "갱신"되는 것은 카테고리뿐이다.
  - 할당량: 살아 있는 판정 별칭이 N개면 하루 약 N/29/50단위다. 1만 개라도 하루 약 7단위라 할당량 문제는 아니다. 다만 위 이유로 지우기를 추천한다.
- 지우기의 비용: 같은 영상이 다시 올라오면 MusicBrainz 식별을 다시 한다. 초당 1회 줄이므로 별칭 1개당 약 1~3초가 든다.

## 4. 기존 로컬 데이터 처리 (V31 안에서 한 번)
- 로컬 실측(11-14)
  - `title_alias` 419개 중 Topic이 아닌 키는 65개다. 지금 남은 세션 근거로 판정 경로뿐인 것도 65개(MATCHED 49)다.
  - `title_guess` 257개 중 판정 그룹은 207개다. `medley_song`은 50개다.
  - `identify_target`의 판정형 행은 130개다. 29일이 지난 세션은 0개다.
- 채우기 규칙 (보수적으로)
  - `identify_target`·`title_guess`·`medley_song`: 남아 있는 `listen_event`와 `video_check`로 계산한다.
  - `title_alias`
    - 판정 없는 근거(`identify_target`의 MUSIC_APP·Topic 그룹, Topic 키)가 있으면 NULL로 둔다.
    - 근거가 없는 Topic 아닌 키에는 `category_checked_at = checked_at`을 넣는다. 잃은 세션 몫까지 판정분으로 본다.
  - `title_guess`·`medley_song` 중 근거를 찾지 못한 행도 같은 규칙을 쓴다. `title_guess`는 `checked_at`을, `medley_song`은 시각 컬럼이 없어 V31 실행 시각을 넣는다.
- 로컬 데이터가 모두 11-10 이후 것이라 당장 지워지는 행은 없다. 운영 DB는 아직 없으므로 이 규칙은 로컬과 앞으로의 배포에만 쓰인다.

## 5. 테스트 계획과 작업량
- core·worker 통합 테스트 6개
  1. `playGroups` 표시: 판정만 있는 그룹은 판정 시각, MUSIC_APP과 섞인 그룹과 Topic 그룹은 NULL
  2. 별칭 저장·캐시 맞힘: 판정 없는 그룹이 오면 NULL로 바뀌고, 판정 그룹끼리는 greatest, NULL은 유지
  3. 정리: 29일 경계(앞뒤 1분)로 별칭·추측·메들리·`identify_target`만 지운다. 판정 없는 행과 곡·방 노래는 남는다
  4. 색인 다시 만들기: 지운 별칭의 YouTube 표기가 `track_search_term`에서 빠지고, 곡명·가수 이름은 남는다
  5. 추측 재사용 경로에서 판정 없는 세션이 오면 NULL로 바뀐다
  6. V31 채우기 규칙 하나(근거 없는 non-Topic 키 → `checked_at`)
- api: 결과 화면 회귀 확인용으로 기존 `SessionApiIntegrationTest`를 돌리고, 별칭을 지운 뒤 응답 200·표시 이름 대체를 보는 테스트 1개를 더한다.
- 작업량
  - 마이그레이션·채우기 0.25일
  - 표시 경로(`Target`·`playGroups`·`saveAlias`·planner·guess·medley) 0.5일
  - 정리와 색인 다시 만들기 0.25일
  - 테스트 0.25~0.5일
  - 합계 약 1~1.5일

## 범위 밖으로 남기는 것 (확인 필요)
- `track.plays_total`: 판정분 재생 수가 더해진 누적 숫자다. 출처를 나눠 두지 않아 빼낼 수 없다. API 값 자체가 아니라 사용자 재생 횟수라 이번 범위에서는 뺀다.
- `track`·`artist`: 판정분 덕에 식별된 곡이라도 값은 MusicBrainz 데이터라 남긴다.
- `track_descriptor`·`track_vector`: 별칭 표기를 입력에 쓴 LLM 설명이다. 로컬에서는 LLM이 꺼져 있다. 원칙상 파생 데이터로 볼 여지가 있어, 관리 세션이 정책 원문을 확인할 때 같이 판단하기를 권한다.
