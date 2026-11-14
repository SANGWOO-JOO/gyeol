# I-48 3번: YouTube API 데이터 보관 기간 점검 (11-14, 읽기 전용)

코드·DB·설정은 바꾸지 않았다. YouTube API도 부르지 않았다.

## 결론
- 지금 YouTube API에서 받은 값을 저장하는 곳은 **`video_check` 테이블 하나**다. 정리 작업이 30일 지난 행을 지운다. 조회할 때도 30일 안의 행만 본다.
- 30일을 분명히 넘기는 저장소는 **0곳**이다.
- 경계 문제가 **1곳** 있다. 정리 작업은 하루 한 번(04:00 KST) 돈다. 그래서 행이 최대 약 **31일**까지 남을 수 있다. 정리가 실패하면 그만큼 더 길어진다.
- 그 밖에 **확인할 점 2개**가 있다. 하나는 쓰이지 않는 채널 프로필 호출 코드다. 다른 하나는 판정 결과로 고른 기록을 오래 두는 문제다. 이것은 보관 기간 규정이 아니라 파생 데이터 규정(III.E.4.h)에 걸린다. 아래 4절에 적었다.

## 1. YouTube API를 부르는 곳

| # | 위치 | 엔드포인트 | 받는 필드 | 목적 | 지금 호출하는 곳 |
|---|---|---|---|---|---|
| 1 | fetcher `youtube/YouTubeClient.kt` `categories()` | `GET /videos?part=snippet&fields=items(id,snippet(categoryId))`, 50개씩 | 영상 ID, categoryId | 일반 영상이 음악(카테고리 10)인지 판정 (L2-3b) | api `video/VideoCheckController.kt:56` → fetcher `POST /internal/youtube/categories` |
| 2 | fetcher 같은 파일 `channelImage()` | `GET /videos`(channelId) + `GET /channels`(thumbnails.medium.url). 기본 프로필인지 보려고 이미지를 받지만 저장하지 않는다 | channelId, 프로필 이미지 URL | 가수 사진 대체 | **없음.** I-45(11-14)부터 worker가 부르지 않는다. 엔드포인트 `GET /internal/youtube/channel-image`와 worker `FetcherClient.channelImage()`만 남아 있다 |

- api·worker·web에는 YouTube API를 직접 부르는 곳이 없다. web에서 YouTube를 언급하는 곳은 Takeout 안내 문구와 `video-check` 호출뿐이다.
- 할당량: 호출 1번에 1단위다. 1번 호출은 업로드 세션마다 영상 ID 최대 1,000개를 보내므로 최대 20번이다. 캐시에 있는 ID는 다시 묻지 않는다.

## 2·3. 저장소별 판정

| 저장소 | 저장하는 값 | 출처 | 지금 보관 기간·규칙 | 30일 판정 | 근거 코드 |
|---|---|---|---|---|---|
| DB `video_check` | video_id, category_id(찾지 못한 영상은 NULL), checked_at | **API**(#1). video_id는 브라우저가 보낸 Takeout 값이다. category_id는 API 값이다 | 조회는 `checked_at > now-30d`만 본다. 정리 작업이 매일 04:00에 `checked_at < now-30d`를 지운다. 다시 물으면 upsert로 checked_at을 갱신한다 | **지킴(경계 주의)**: 정리가 하루 한 번이라 최대 약 31일 남는다. 30일이 지난 행은 조회에서 빠져 쓰이지 않는다 | `core/video/VideoCheckRepository.kt`, `api/video/VideoCheckController.kt:52,70`, `worker/cleanup/CleanupService.kt:51`, `CleanupScheduler.kt:17`, `V9__video_check.sql` |
| DB `artist_image` / `channel_image` (source=YOUTUBE) | 예전에는 프로필 URL을 저장했다(S3에는 저장하지 않음) | API(#2) | V30이 YOUTUBE 행을 모두 지웠다. 새로 쓰는 코드도 없다. CHECK 제약 값만 남아 있다 | **해당 없음**(로컬 0행) | `V30__drop_youtube_artist_images.sql`, `worker/artistimage/ArtistImages.kt:42` |
| DB `artist_image` / `channel_image` (COMMONS·NONE), S3 `artists/`·`channels/` | Commons 사진, 저작자, 라이선스 | Wikimedia | — | 해당 없음(YouTube 아님) | — |
| DB `channel_image.channel` | Topic 채널 이름 | **Takeout**(사용자 시청 기록의 채널 이름). API가 아니다 | 전역 캐시, 계속 보관 | 해당 없음 | `V19__channel_image.sql` |
| DB `listen_event` (video_id·title·channel, source=YOUTUBE) | 시청 기록의 영상 ID·제목·채널 | **Takeout**. 다만 일반 영상은 API 판정이 음악이라고 한 것만 브라우저가 올린다 | expires_at 7일, 정리 작업이 지운다 | 해당 없음(API 값을 저장하지 않음). 7일이라 30일 안이다 | `V1__init.sql:43`, `CleanupService.kt:48`, `web/src/pages/Upload.tsx:44` |
| DB `identify_target`·`title_alias`·`track_search`·`title_guess` | Takeout 제목·채널, 사용자가 본 YouTube 표기 | **Takeout**. 일반 영상 몫은 API 판정을 거쳐 들어왔다 | 전역 데이터로 계속 보관한다(`V4__identify.sql:1`) | 보관 기간은 해당 없음. **4-2 파생 데이터 문제 참고** | `V4__identify.sql`, `IdentifyRepository.kt:72` |
| fetcher 메모리 캐시 `TtlCache`(10분) | MusicBrainz·Last.fm 검색 결과 | YouTube 아님 | YouTube 결과는 캐시하지 않는다 | 해당 없음 | `MusicBrainzClient.kt:28`, `LastFmClient.kt:27` |
| Redis | 설정에 없음 | — | — | 해당 없음 | application.yml |
| 로그 | 요청 수·응답 수·호출 수·ms, 오류 상태 코드 | API | 영상 ID·카테고리·키를 남기지 않는다. 키는 일부러 숨긴다 | 해당 없음 | `YouTubeClient.kt:48,57`, `VideoCheckController.kt:61` |
| 브라우저 저장소 | 판정 결과를 저장하지 않는다. 메모리 Set만 쓴다 | — | — | 해당 없음 | `web/src/pages/Upload.tsx:44` |

### 같은 컬럼에 섞이는 경우
- `video_check.video_id`: 값은 사용자의 Takeout에서 왔다. 하지만 행이 담는 사실은 API 응답이다. 그 영상이 존재하는지와 카테고리가 무엇인지다. 행 전체를 API 데이터로 보고 30일 규칙을 적용하고 있다(지금 구조가 맞다).
- `listen_event.source='YOUTUBE'` 행: 값은 모두 Takeout이다. 하지만 어떤 행이 들어올지는 API 판정이 정했다. 컬럼에 API 값은 없다.
- API 값과 Takeout 값이 한 컬럼에 함께 들어가는 경우는 없다. 예전 `artist_image.url`(YOUTUBE 프로필 URL)이 유일한 사례였는데, V30이 지웠다.

### 영상 ID 같은 식별자를 정책이 따로 다루는가
- 정책 원문(III.E.4)을 직접 확인하려 했다. 하지만 받아 온 페이지의 III.E.4 부분이 요약본으로만 와서 **원문 문장으로 확인하지 못했다**.
- 요약에서 확인한 내용은 다음과 같다.
  - Non-Authorized Data(사용자 인증 없이 받은 API 데이터)는 30 calendar days까지 두고, 그 뒤 지우거나 새로 받아야 한다.
  - Authorized Data 중 YouTube Analytics·Reporting API 데이터와 조회수 같은 통계는 따로 다룬다.
  - **영상 ID·채널 ID를 30일 규칙에서 빼 주는 조항은 찾지 못했다.**
- 그래서 이 보고서는 보수적으로 판단했다. API 응답으로 얻은 식별자도 30일 규칙 대상이다. 예를 들어 `channelImage()`가 받는 channelId가 그렇다.
- 우리가 저장하는 영상 ID는 Takeout(사용자가 직접 올린 자기 기록)에서 온 것이다. 그래서 API 데이터가 아니라고 본다. 다만 이것도 추론이다.
- 배포 전에 https://developers.google.com/youtube/terms/developer-policies 의 III.E.4 원문을 사람이 직접 한 번 확인할 것을 권한다.

## 4. 고칠 점과 방안

### 4-1. `video_check` 31일 경계 (작게 고침)
- **방안 A(추천):** 정리 기준과 조회 기준을 **29일**로 당긴다. 정리 작업이 하루 늦어도 30일 안에 지워진다.
  - 할당량: 다시 묻는 시점이 하루 당겨질 뿐이라 영향은 거의 없다.
  - 작업량: 상수 2곳(`VideoCheckController.RETENTION`, `CleanupService` 30일)을 바꾸고, 공용 상수로 합치고, 테스트 1개를 더한다. 약 30분이다.
- **방안 B:** 정리 cron을 하루 여러 번(예: 6시간마다) 돌린다.
  - 할당량 영향: 없다.
  - 작업량: 설정 1줄이다. 하지만 정리 작업에는 방 완전 삭제 등 다른 일도 함께 있어 더 자주 돈다. 그래서 A가 낫다.
- 참고: 30일이 지난 행은 이미 조회에서 빠져 쓰이지 않는다. 남아 있는 시간만 하루 정도 길다.

### 4-2. 판정 결과로 고른 기록을 오래 두는 문제 (보관 기간이 아니라 III.E.4.h)
- 요약된 III.E.4.h에 따르면 API 데이터로 "새로 만들거나 파생한 데이터·지표"를 만들면 안 된다.
- 우리 구조에서 API 값(categoryId)은 업로드할 기록을 고르는 데만 쓴다. 저장하는 값은 사용자 자신의 Takeout 기록이다. 보수적으로 보면, "음악으로 판정된 일반 영상" 제목이 전역 식별 데이터에 계속 남는다(`title_alias`·`track_search`·`title_guess`).
- 여기에 파생 데이터 규정이 적용되는지는 해석 문제다. 그래서 **판정하지 않고 확인할 점으로 둔다**.
- 신경 쓰인다면 두 방안이 있다.
  - **방안 A:** 전역 테이블에 남길 때 판정 경로로 들어온 행을 표시한다(예: `via_category=true`). 그렇게 표시된 행은 30일 뒤 지우거나, 다시 판정해 갱신한다.
    - 할당량: 다시 판정하면 50개당 1단위다. 판정을 거친 고유 영상 수가 하루 N개 늘면 하루 약 N/50단위다. 하루 기본 할당량 10,000단위에 비해 매우 작다.
    - 작업량: 마이그레이션, 표시 경로, 정리 작업까지 0.5~1일이다.
  - **방안 B:** 판정을 거친 일반 영상은 세션 범위 데이터(listen_event, 7일)에만 두고 전역 식별 테이블에는 넣지 않는다.
    - 할당량: 0이다.
    - 작업량: 0.5일이다. 대신 같은 영상이 다음 세션에서 다시 식별되므로 MusicBrainz 호출이 늘어난다.

### 4-3. 쓰이지 않는 채널 프로필 코드 (정리 권장)
- `YouTubeClient.channelImage()`, fetcher `GET /internal/youtube/channel-image`, worker `FetcherClient.channelImage()`를 부르는 곳이 없다.
- 남겨 두면 누가 다시 쓰기 시작했을 때 30일 규칙과 출처 표시 의무가 함께 돌아온다.
- **방안:** 세 곳을 지운다. 기본 프로필 판별 `DefaultAvatar.kt`도 다른 사용처가 없으면 함께 지운다. 할당량은 0으로 줄어든다(지금도 0). 작업량은 30분이다.
- `artist_image`·`channel_image` CHECK 제약의 'YOUTUBE' 값은 다음 마이그레이션에서 뺄 수 있다(선택).

## 5. 로컬 DB 실측 (11-14, 기준 now-30일)

| 테이블 | 전체 | 30일 넘은 행 | 가장 오래된·최근 |
|---|---|---|---|
| video_check | 1,000 (음악 344, 못 찾음 0) | **0** | checked_at 11-10 ~ 11-10 |
| artist_image | 1,485 (YOUTUBE 0) | 0 | checked_at 11-14 |
| channel_image | 74 (YOUTUBE 0) | 0 | checked_at 11-10 ~ 11-14 |
| listen_event | 62,592 | 만료된 행 0 (expires_at 11-17) | watched_at은 Takeout 시청 시각이라 보관 기간과 관계없다 |

- 로컬 데이터가 모두 최근 것이라 30일 넘은 행이 없다. 이것은 정리 작업이 맞게 도는지 보여 주는 증거가 아니다. 정리 작업이 맞는지는 코드로 확인했다.
- psql은 `docker exec … sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" …'` 형태로만 썼다. env 값은 출력하지 않았다.
