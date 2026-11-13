# 음악 방 API·DB 설계 (2026-11-13)

상태: 설계 승인(11-13), 구현 전. 기획은 `room-plan.md`, 화면은 프로토타입 캔버스(보드 번호를 그대로 씀).

## 0. 전제

- 방은 기존 `session`(토큰 링크·30일 보관)을 쓰지 않고 새 테이블로 만든다. 영구 보관·여러 참가자라 수명과 권한이 다르다.
- 재사용: `artist`·`track`, 곡 검색(DB 먼저 + Last.fm), identify·enrich 큐, `knowledge_chunk`, e5 임베딩, 표지·가수 이미지, `llm_call`(비용 기록).
- 마이그레이션은 영역별로 V21~V26.

## 1. DB

### V21 방·참가자·인증

| 테이블 | 주요 열 | 메모 |
|---|---|---|
| `room` | `public_id`(링크용 무작위 10자), `name`, `status`(ACTIVE·CLOSED·HIDDEN), `map_rev`, `hidden_at`, `purge_at`, `created_at`, `last_active_at` | `map_rev`는 지도가 바뀔 때마다 +1 → 지도 새로고침에 ETag로 써서 바뀐 게 없으면 304 |
| `room_host_email` | `room_id`(PK), `email`, `consent_at` | 방 완전 삭제 때 함께 삭제. 관리자 화면은 일부 가림 |
| `member` | `room_id`, `nickname`, `role`(HOST·MEMBER), `color_idx`(0~4), `pin_hash`(BCrypt), `fail_count`, `locked_until`, `avatar_character_id`, `removed_at` | 방 안 닉네임 중복 금지(대소문자 무시, 내보낸 사람 제외 부분 유니크 인덱스). 5명 제한은 `room` 행 잠금 후 셈 |
| `member_key` | `key_hash`(SHA-256, PK), `member_id`, `created_at`, `last_seen_at`, `revoked_at` | 기기 열쇠. 원문은 쿠키·localStorage에만 |
| `room_draft` | 방 이름·닉네임·`pin_hash`·이메일, `expires_at`(1시간) | "방 만들기 → 메일 코드 → 방 생성" 순서라 인증 전 내용을 잠시 보관 |
| `email_code` | `purpose`(CREATE·HOST_RESET), 대상(draft 또는 room), `code_hash`, `expires_at`(10분), `attempts`(5회), `last_sent_at`(다시 받기 1분) | 방장 인증·재설정 공용 |
| `mail_send_log` | `email_hash`, `ip`, `sent_at` | 이메일별·IP별 발송 제한 |
| `audit_log` | `actor_type`(ADMIN·HOST·SYSTEM), `actor_id`, `room_id`, `action`, `target_member_id`, `detail`(JSON), `created_at` | 관리자 상세(7-c)의 기록 |

### V22 노래 담기

| 테이블 | 주요 열 | 메모 |
|---|---|---|
| `room_song` | `room_id`, `member_id`, `track_id`, `artist_id`(중복 저장), `added_at` | UNIQUE(room_id, member_id, track_id). 금빛 숫자 = 같은 (room, track)의 사람 수 |
| `song_request` | `room_id`, `member_id`, 입력한 제목·가수·mbid, `status`(IDENTIFYING·PREPARING·DONE·FAILED), `track_id`, `fail_reason` | DB에 없는 곡을 식별하는 동안의 상태 → 4-g·4-i |
| `member_daily` | `member_id`, `day`(한국 날짜), `songs_added` | 하루 100곡. 빼도 줄지 않게 따로 셈 |

### V23 음악 지도 (모든 방 공용)

| 테이블 | 주요 열 | 메모 |
|---|---|---|
| `artist_profile` | `artist_id`, `country`, RAG 설명, 근거 조각 id들, `text_hash` | 가수 단위 설명 |
| `artist_vector` | 원문 벡터, 설명 벡터, 섞은 벡터(HNSW) | 원문 0.5 + 설명 0.5 (room-plan 8절) |
| `map_version` | 만든 시각, UMAP 설정, 가수 수 | 좌표는 판별로 고정 |
| `artist_position` | `artist_id`, `map_version`, `x`, `y`, `placed_by`(UMAP·NEIGHBOR) | 새 가수 = 가까운 이웃 좌표 평균 |
| `galaxy`, `artist_galaxy` | 구역 이름, 중심, 근거 id들 | 장르 구역 이름 |
| `artist_prepare` | `artist_id`, `status`, `step`, 오류 | 새 가수 지도 올리기 진행 상태. 1인 동시 3개 |

### V24 50곡 선물

| 테이블 | 주요 열 | 메모 |
|---|---|---|
| `member_progress` | `member_id`, `best_count`(최고 기록), `next_milestone` | 빼고 다시 담아 같은 단계를 넘어도 상자 없음 |
| `gift_box` | `member_id`, `milestone`, `status`(WAITING·READY·GENERATING·OPENED·FAILED), `earned_at`, `ready_on`, `opened_at`, `character_id` | 하루 1개(`(member_id, ready_on)` 고유 색인). 넘친 상자는 WAITING |
| `gift_character` | `member_id`, `gift_box_id`, `s3_key`, 프롬프트, 모델, `is_fallback` | 비공개 S3(covers 버킷 `characters/`) + API 전달. 기본 캐릭터는 `preset:N`. 표 이름은 SQL 예약어 `character`를 피함 |
| (기존 `llm_call`) | 이미지 호출도 기록 | 서비스 전체 하루 예산 계산 |

### V25 놀이터

| 테이블 | 주요 열 | 메모 |
|---|---|---|
| `code_match` | `room_id`, `member_a`<`member_b`, `input_hash`(두 사람 곡 목록 + 지도 판), `score`, `parts`(항목별 점수·겹친 가수·구역, JSON), `reason`, `evidence`, `status`(REASONING·DONE·NO_REASON) | 곡이 그대로면 저장 결과 재사용 |

### V26 관리자

- `admin_user`: 이메일, BCrypt 비밀번호, TOTP 비밀값(AES-GCM 암호문, 키는 환경 변수 `ADMIN_TOTP_KEY`), 마지막 TOTP 칸, 실패 횟수·잠금.
- `admin_session`: 토큰 해시, 단계(PENDING_OTP 5분 → ACTIVE 1시간), IP, 폐기 시각.
- `room.status_before_hide`(되살릴 때 원래 상태), `audit_log.room_id` 참조를 ON DELETE SET NULL로(감사 기록 1년 보관).
- 계정 행은 운영 DB에 직접 INSERT. 비밀번호·해시는 저장소에 넣지 않는다.

## 2. 인증

- 참가하면 32바이트 열쇠 발급.
  - HttpOnly 쿠키(SameSite=Lax, Path=`/api/rooms/{rid}`) → 방마다 따로.
  - localStorage에도 저장해 `X-Room-Key` 헤더로 보냄 (카톡 안 브라우저처럼 쿠키가 잘 안 남는 경우).
- 방장 전용 API는 `role=HOST` 확인.
- 4자리 암호는 참가자별 5회 실패 10분 잠금.
- 요청 제한:
  - 분 단위 IP 제한은 서버 메모리. 단일 서버는 정확, EKS 여러 파드면 파드별 근사.
  - 하루 100곡·메일 발송 제한은 DB.
- **링크만 받은 사람은 지도를 볼 수 없다**(11-13 결정). 열쇠 없이 부를 수 있는 방 API는 `GET /rooms/{rid}`(방 이름·사람 수·닉네임·상태)뿐.

## 3. API (`/api`)

| 영역 | 엔드포인트 | 화면 |
|---|---|---|
| 방 만들기 | `POST /room-drafts` → `POST /room-drafts/{id}/verify`(방 생성 + 열쇠) · `POST /room-drafts/{id}/resend`. 만들기·다시 받기 응답: `expiresAt`(초안 1시간), `resendAfter`, `codeExpiresAt`(방금 보낸 코드 10분, 11-13 추가) | 2, 3 |
| 초대·참가 | `GET /rooms/{rid}`(공개 요약) · `POST /rooms/{rid}/members`(참가) · `POST /rooms/{rid}/session`(나는 OO예요 + 4자리, 잠김 423) · `DELETE /rooms/{rid}/session`(이 기기에서 나가기) | 5, 6-a, 6-g, 6-h |
| 내 정보 | `GET /rooms/{rid}/me`(닉네임·선물 상태·오늘 담은 수) · `PATCH /rooms/{rid}/me`(닉네임) | 6-h, 4-l |
| 지도 | `GET /rooms/{rid}/map`(ETag=`map_rev`, 5초 새로고침) · `GET /rooms/{rid}/artists/{id}`(방 안 노래·설명·가까운 가수) | 4, 4′ |
| 검색·담기 | `GET /rooms/{rid}/search?q=`(같은 아티스트·같은 노래·이미 담음·새 가수 표시) · `POST /rooms/{rid}/songs` → 201(담김, 겹침·선물 여부) 또는 202(`song_request`) · `GET /rooms/{rid}/song-requests/{id}` · `DELETE /rooms/{rid}/songs/{id}`(빼기·되돌리기) | 4-d, 4-e, 4-g, 4-h, 4-i |
| 내 노래 | `GET /rooms/{rid}/me/songs?sort=recent\|genre\|artist&cursor=` | 4-a |
| 방장 | `GET /rooms/{rid}/members` · `POST /rooms/{rid}/members/{mid}/pin`(새 4자리를 한 번만 보여 줌) · `DELETE /rooms/{rid}/members/{mid}`(내보내기) · `PATCH /rooms/{rid}`(이름·마감) · `DELETE /rooms/{rid}`(숨김, 7일 뒤 완전 삭제) | 4-b, 6-c, 6-e, 6-f |
| 방장 암호 재설정 | `POST /rooms/{rid}/host-reset`(등록 메일로 코드, 주소는 화면에 안 보임) · `POST /rooms/{rid}/host-reset/verify`(코드 + 새 암호). 11-13 구현: 열쇠 없이 부름, 보내기 202 `{codeExpiresAt, resendAfter}`(이메일 없음, 없는·숨긴 방은 `GET /rooms/{rid}`처럼 404), 확인 200 = 다시 들어오기와 같은 응답(열쇠·쿠키), 방장의 다른 기기 열쇠는 모두 폐기하고 이번 열쇠만 남김(11-13 I-10 결정, 감사 기록 `revokedKeys`. 참가자 새 4자리는 열쇠 유지 그대로), 코드 5번 틀리면 잠금·다시 받기 1분·메일 1시간 제한(같은 이메일 5통·같은 IP 20통, 방 만들기와 공유)·IP 분당 제한, 감사 기록 `HOST_RESET_SENT`·`HOST_PIN_RESET` (`HostResetController`) | 6-d |
| 선물 | `GET /rooms/{rid}/me/gifts` · `POST /rooms/{rid}/me/gifts/{id}/open`(202) · `GET /rooms/{rid}/me/gifts/{id}` · `PUT /rooms/{rid}/me/avatar` · `GET /rooms/{rid}/characters/{id}/image` | 4-j, 4-k |
| 놀이터 | `POST /rooms/{rid}/code-match {a,b}`(저장 결과 200, 없으면 202) · `GET /rooms/{rid}/code-match?a=&b=` | 8-a, 8-b |
| 관리자 | `POST /admin/login` → `POST /admin/login/otp` · `GET /admin/rooms` · `GET /admin/rooms/{id}`(담긴 노래 목록은 보이지 않음, 곡 수만, 11-13 I-19) · 숨김·되살리기 · 닉네임 변경·내보내기 · `GET /admin/audit` | 7-a~7-c |

## 4. 비동기 (SQS + 실패 큐)

| 큐 | 하는 일 |
|---|---|
| `mail` | SES 발송, 로컬은 가짜 메일함 |
| identify·enrich (기존) | DB에 없는 곡 식별 → `song_request` 갱신. 식별되면 바로 `room_song` 저장(위성 표시), 곡 설명·벡터는 뒤에서 (`room-measure.md` 3절) |
| `artist-prepare` | 새 가수: 근거 → RAG 설명 → 벡터 → 이웃 평균 좌표 → 지도에 반영 |
| `gift-image` | Haiku 프롬프트 → us-west-2 이미지 모델 → S3. 실패·예산 초과면 기본 캐릭터 |
| `code-match` | 점수는 API에서 SQL로 바로, LLM은 이유만 |

- 초기 가수 1,500명 지도 만들기는 한 번 돌리는 worker 작업이다. UMAP만 Python 임베딩 서비스에서 계산한다.
- 방 완전 삭제(숨긴 지 7일 뒤)는 기존 정리 작업(worker 매일 04:00)에 붙인다.

## 5. 한 트랜잭션에서 지키는 규칙

- 참가(5명), 노래 담기(하루 100곡 + 선물 단계), 새 암호 발급.
- 담기: 곡 수 ≥ 다음 50곡 단위이고 최고 기록보다 크면 상자를 만든다. 그날 이미 받았으면 WAITING.
- 지도를 바꾸는 쓰기(담기·빼기·닉네임·참가·내보내기)는 같은 트랜잭션에서 `map_rev`를 올린다.

## 6. 결정 (11-13)

| # | 항목 | 결정 |
|---|---|---|
| 1 | 링크만 받은 사람의 지도 보기 | **볼 수 없음.** 초대 화면은 방 이름·사람 수·닉네임만, 지도·노래는 참가 후 |
| 2 | 방장 우주 삭제 | **바로 숨김 → 7일 뒤 완전 삭제**(이메일·노래·캐릭터 이미지). 7일 안에는 관리자만 되살림. 화면 문구는 그대로, 개인정보 안내에 "삭제 후 7일 안에 완전히 지워져요" 추가 |
| 3 | 구현 순서 | ⓪ 측정·P0 → ① 방·참가·담기(V21~22, 지도는 임시 좌표) → ② 지도 데이터(V23) → ③ 메일(SES) → ④ 선물(V24) → ⑤ 놀이터(V25) → ⑥ 관리자(V26) |

## 7. ① 구현 기록 (11-13)

- 마이그레이션: `V21__room.sql`(room·room_host_email·member·member_key·room_draft·email_code·mail_send_log·audit_log), `V22__room_song.sql`(room_song·song_request·member_daily)
- core `room/`: `RoomRepository`, `RoomDraftRepository`, `RoomSongRepository`
- api `room/`: `RoomDraftController`(방 만들기·코드), `RoomController`(공개 요약·참가·다시 들어오기·나가기·내 정보·방장), `RoomSongController`(검색·담기·식별 요청·빼기·내 노래·지도), `RoomSupport`(열쇠 쿠키·헤더, `@RoomMember`, BCrypt, IP 분당 제한, 커밋 뒤 큐, 가짜 메일)
- worker: `IdentifyService`에서 곡 식별 부분을 `identifyTargets(targets, sink)`로 떼어 냄(세션 묶음은 그대로 identify_target에, 방은 song_request에). `room/RoomSongService`·`RoomSongListener`(큐 `room-song` + 실패 큐), 정리 작업에 숨긴 방 7일 뒤 삭제·만료된 방 만들기 내용 삭제 추가
- 큐: 로컬 `local/localstack/init-aws.sh`에 `gyeol-local-room-song`(+dlq, 표시 제한 15분). Terraform·Helm 값은 배포 단계에서
- 테스트: api `RoomApiIntegrationTest` 8개, worker `RoomSongIntegrationTest` 5개

### 이번 단계에서 뺀 것·임시
- 메일은 `LogMailSender`(로그에 코드). SES는 ③
- 지도 좌표는 가수 mbid 해시로 만든 임시 좌표(`placeholder: true`). ②에서 `artist_position`으로 바꿈
- 내 노래 정렬은 최근 담은 순만. 장르별은 ②(가수 설명·구역) 뒤, 아티스트별은 웹 화면 만들 때
- 행성 자세히(`GET /rooms/{rid}/artists/{id}`)는 ②에서 설명과 함께
- 웹 화면은 아직 없음 (② 뒤, 지도 화면은 Codex)

## 8. ② 지도 데이터 구현 기록 (11-13)

- 마이그레이션 `V23__artist_map.sql`: artist_profile(나라·초기 가수 순위·설명·근거), artist_vector(raw·descr·mixed, HNSW), map_version(활성 하나), galaxy, artist_position(UMAP·NEIGHBOR), artist_prepare
- fetcher `MapController`: `GET /internal/lastfm/geo-top`(나라별 인기 가수), `GET /internal/musicbrainz/artist`(출신 국가·별칭, mbid 또는 이름 검색 1위 95점 이상)
- worker `map/`: `MapService`(seed·describeAll·embedAll·nameGalaxies·prepare·place), `MapLlm`(가짜·Bedrock, 11-11 프롬프트 + 근거 번호, 근거 없는 설명은 버림), `MapAdminController`(`/internal/map/…`, `MAP_ADMIN_ENDPOINT=true`일 때만, 한 번에 작업 하나)
- 새 가수: 방에 담긴 가수가 활성 판에 자리가 없으면 `room-song` 큐에 PREPARE_ARTIST(한 번만) → 근거·설명·벡터 → 이웃 5명 평균 자리·구역 다수결 → 그 가수가 담긴 방 map_rev +1
- `tools/map-build/build.py`(uv): 모든 가수 벡터 → UMAP(cosine, 이웃 10, min_dist 0.1) → 0.04~0.96 → KMeans(√(n/2), 6~60) → 새 판 활성 + 모든 방 map_rev +1. `--dry-run`, `--eval`(Last.fm 비슷한 가수 일치율)
- API: 지도 응답에 진짜 좌표·galaxyId·preparing, galaxies(방에 행성이 있는 구역), ghosts(최근 가수 곁 방에 없는 가까운 가수, 최대 3). `GET /rooms/{rid}/artists/{id}`(설명·근거 링크·방 안 노래·가까운 가수 5)
- 테스트: worker `MapIntegrationTest` 4개, api 방 테스트 9개(+지도 판)

### 실행 순서 (로컬)
1. worker를 `MAP_ADMIN_ENDPOINT=true LLM_MODE=bedrock`으로 켠다
2. `POST /internal/map/seed {"countries":[{"iso":"KR","lastfm":"South Korea"},{"iso":"JP","lastfm":"Japan"},{"iso":"US","lastfm":"United States"}],"perCountry":100}` → `GET /internal/map/job`
3. `POST /internal/map/describe {"limit":2000}` (비용) → `POST /internal/map/embed`
4. `uv run --directory tools/map-build python build.py --eval`
5. `POST /internal/map/name-galaxies` (비용)

### 알려진 한계
- 근거를 90일 뒤 다시 가져오면 knowledge_chunk id가 바뀌어 설명의 근거 번호가 끊긴다. 다시 설명을 만들 때 고친다

## 9. ④ 선물 구현 기록 (11-13)

- 결정(11-13): 이미지 모델 Stable Image Core(us-west-2, 장당 약 $0.04, 화풍은 프롬프트로 고정), 서비스 전체 하루 50장, 기본 캐릭터 6종(SVG, `core/src/main/resources/gift/`), 실제 Bedrock 이미지 호출은 Marketplace 구독 확인 뒤 따로.
- 마이그레이션 `V24__gift.sql`: member_progress·gift_box·gift_character, `member.avatar_character_id`(V21 열)에 참조 추가.
- core `GiftRepository`: `onSongAdded`(담기와 같은 트랜잭션, 방 행 잠금 안), `promoteWaiting`(다음 날 첫 조회 때 하루 1개), `summary`, 열기 시작·끝, `genreMix`(구역 이름 비율, 가수 이름 없음), `imagesToday`, 완전 삭제할 방의 그림 키.
- api: `RoomSongService.add` 201 응답에 `gift`, `GET /me`에 `gift` 요약, `GiftController`(목록·상자·열기 202·얼굴 바꾸기·그림 전달). 그림은 같은 방 참가자만, `Cache-Control: private`, SVG는 `Content-Security-Policy: default-src 'none'`.
- worker: 식별 경로 담기(`RoomSongService.identifyRequest`)에도 `onSongAdded`. `gift/GiftService`·`GiftListener`(큐 `gift-image` + 실패 큐 → FAILED): 구역 비율 → Haiku 묘사 → 이미지 → S3 → 캐릭터. 하루 예산 초과·생성 실패는 기본 캐릭터. `llm_call`에 `GIFT_PROMPT`·`GIFT_IMAGE` 기록. 가짜 모드는 기본 그림을 S3에 올린다.
- 정리 작업: 방 완전 삭제 전에 캐릭터 그림을 S3에서 지우고, 실패하면 그 날은 방을 지우지 않는다.
- 설정: api `gyeol.room.gift-queue`, worker `gyeol.worker.gift.*`, 로컬 큐 `gyeol-local-gift-image`(+dlq, 표시 제한 5분). Terraform·Helm은 배포 단계에서(room-song 큐와 함께).
- 테스트: api `RoomApiIntegrationTest` 선물 1개(50곡째 상자·요약·열기·그림 권한·기본 그림·얼굴), worker `GiftIntegrationTest` 4개(50곡·최고 기록·하루 1개·다음 날, S3 저장·중복 메시지, 예산 초과, 실패 큐), `RoomSongIntegrationTest` 식별 경로 50곡째 1개. 전체 api 27·worker 70 통과.
- 로컬 확인(11-13, fake): 개발 서버에서 50곡 담기 → 상자 READY → 열기 → 1초 안에 OPENED → 그림 200(S3 `characters/…svg`).
- 아직: 실제 Bedrock 이미지 호출(요청·응답 형식은 문서 기준, 미확인), 웹 화면(갈래 A: 4-j·4-k·4-l).
- 11-13 추가: `GET /me/gifts/{id}` 응답에 `genreMix`([{galaxy, songs}] 상위 4개, OPENED 상자만). 상자를 연 시각까지 담은 노래로 다시 센다(따로 저장하지 않아, 연 뒤에 뺀 노래·바뀐 지도 판은 반영되지 않는 근사). 가수 이름 없음.
- 11-14 변경(room-plan.md "⑯ 변경: 새 그림은 하루 10장 선착순"): 서비스 전체 하루 새 그림 한도 50 → 10(`GIFT_DAILY_IMAGE_LIMIT`, worker `gyeol.worker.gift.daily-image-limit`·api `gyeol.room.gift-daily-image-limit`가 같은 환경 변수, Helm `gift.dailyImageLimit`). `GET /me`의 `gift`와 `GET /me/gifts/{id}`·열기 응답에 `imageDailyLimit`·`imagesLeftToday`(= max(0, 한도 − 오늘 `llm_call` GIFT_IMAGE 수), 가짜 모드는 세지 않음). 열지 않은 READY 상자는 다음 날에도 READY로 남아 내일 열 수 있다(테스트로 확인). 남은 장수 0에서 열면 worker가 기본 캐릭터. 캐릭터 응답 `fallbackReason`: `DAILY_LIMIT`(열 때 한도를 다 씀, 동시 열기 포함) · `FAILED`(생성·저장 실패), `gift_character.model`에 `preset:DAILY_LIMIT`·`preset:FAILED`로 남김(마이그레이션 없음, 11-14 전 기본 캐릭터 `preset`은 FAILED로 봄).

## 10. ③ 메일 구현 기록 (11-13, 갈래 B)

- 메일 코드를 `RoomSupport.kt`에서 `backend/api/.../api/mail/`로 옮김: `MailSender`(인터페이스, 시그니처 그대로) · `LogMailSender`(가짜 메일함, `mask` 포함) · `MailTemplates`(CREATE·HOST_RESET 한국어 제목·텍스트·HTML) · `SesMailSender` + `MailProperties`·`MailConfig`
- 고르기: `gyeol.mail.mode`(`MAIL_MODE`) log(기본, 값이 없어도) | ses. ses는 `MAIL_FROM` 필수(없으면 시작 실패), `MAIL_CONFIG_SET` 선택. 발신 표시 이름 "내 음악 결"(RFC 2047)
- SES 실패(샌드박스에서 인증 안 된 받는 사람 포함) → 503 `MAIL_FAILED`. 로그에는 가린 주소와 messageId만 남기고 코드는 남기지 않는다
- 테스트: `SesMailSenderTest` 6개(가짜 SesV2Client, 실제 AWS 호출 없음). api 전체 32개 통과(방 9 + 세션 17 + 메일 6)
- Terraform `infra/terraform/base/ses.tf`: `mail_domain`이 비면 자원 없음. 값이 있으면 도메인 identity(Easy DKIM 2048) · MAIL FROM `mail.<도메인>` · 설정 세트 `gyeol` · 계정 단위 자동 억제(BOUNCE·COMPLAINT) · 샌드박스 테스트 받는 사람 · (zone id가 있으면) Route 53 DKIM·MX·SPF·DMARC 레코드. 앱 IAM 정책에 `ses:SendEmail`(identity·설정 세트·테스트 받는 사람 ARN, 조건 `ses:FromAddress = noreply@<도메인>`)
- plan(읽기만, apply 안 함): 도메인 없음 29개 추가(base 전체가 아직 apply 전) · 도메인 있음 +SES 5 +레코드 6

### 알려진 한계
- 메일은 코드 저장과 같은 트랜잭션 안에서 보낸다. 실패하면 코드 행·발송 기록이 함께 롤백되지만, 반대로 메일은 나갔는데 커밋이 실패하는 경우가 남는다(사용자는 다시 받기). SES 호출(수백 ms, 추정) 동안 DB 연결을 잡는다. 지금 규모에서는 허용
- `ses:FromAddress` 조건이 표시 이름을 붙인 발신 주소에서도 주소 부분만 비교하는지, 설정 세트·받는 사람 ARN 권한이 꼭 필요한지는 문서 기준 추정. 첫 실제 발송 때 확인
- `HOST_RESET` 문구만 있고 호출하는 API는 아직 없다(6-d) → 11-13 갈래 1이 구현(3절 방장 암호 재설정 줄)

## 11. ⑤ 놀이터 · 코드 맞추기 구현 기록 (11-13)

- 결정(11-13, 관리 세션 전달): 각자 이 방 노래 5곡부터(빼서 5곡 미만이면 409 `NOT_ENOUGH_SONGS`), 비중 장르 40·지도 거리 30·겹친 가수 20·겹친 노래 10(설정값 `gyeol.room.code-match.weight-*`), 방당 하루 새 계산 30번(저장 결과 재사용은 안 셈, 넘으면 429 `CODE_MATCH_LIMIT`), 이유에 가수 이름 허용(근거 번호 규칙 유지). "둘을 이어 주는 곡"은 이번 범위에서 뺌.
- 마이그레이션 `V25__code_match.sql`.
- core `CodeMatch.kt`: `CodeMatchScore`(점수, LLM 없음) — 장르 = 구역별 곡 수 코사인, 지도 거리 = 각자 행성마다 상대의 가장 가까운 행성까지 거리 평균 → 1 − 거리/0.3, 겹친 가수·노래 = 겹친 수 ÷ 적은 쪽 수. 신뢰도 = 둘 중 적은 쪽 곡 수로 LOW(5~9)·MEDIUM(10~19)·HIGH(20~). `CodeMatchRepository`.
- api `CodeMatchController`: `POST /api/rooms/{rid}/code-match {a,b}`(저장 결과 200, 새 계산 202), `GET …?a=&b=`(최근 결과). 응답: 점수·항목·겹친 가수(10)·구역(5)·겹친 노래 수·이유·근거(출처 링크)·상태·두 사람 곡 수·`confidence`·`stale`(그 뒤 곡이나 지도 판이 바뀜). 방 행을 잠가 하루 횟수를 센다. 나간 사람·다른 방 사람은 404.
- worker `codematch/CodeMatchReasonService`·`CodeMatchListener`(큐 `code-match` + 실패 큐 → NO_REASON): 겹친 가수(없으면 각자 많이 담은 가수 2명)의 근거 있는 설명으로 Haiku 2~3문장, 준 번호 밖·근거 없는 이유는 버림. `llm_call`에 `CODE_MATCH`.
- 응답의 `minSongs`는 코드 맞추기가 열리는 하한(설정값 `gyeol.room.code-match.min-songs`, 지금 5)이다. 두 사람 곡 수가 아니다(곡 수는 `a.songCount`·`b.songCount`).
- 설정: api `gyeol.room.code-match-queue`·`gyeol.room.code-match.*`, worker `gyeol.worker.code-match.*`, 로컬 큐 `gyeol-local-code-match`(+dlq, 표시 제한 2분).
- 함께 고침: 방 삭제 시 `hidden_at`을 DB `now()` 대신 앱 시각으로 넣음(앱·DB 시계가 몇 ms 어긋나 "숨긴 뒤 7일"이 6일로 계산돼 기존 테스트가 한 번 실패).
- 테스트: api `CodeMatchScoreTest` 4개 + 측정 1개(로컬 DB 있을 때만), `RoomApiIntegrationTest` 코드 맞추기 1개, worker `CodeMatchReasonIntegrationTest` 2개. 전체 api 39·worker 72 통과.
- 로컬 확인(fake): 개발 서버에서 11곡·16곡(겹침 4곡) 두 사람 → 202, 1초 안에 DONE, 79점(장르 0.73·거리 0.96·가수 0.86·노래 0.36), 신뢰도 MEDIUM.

### 곡 수별 점수 안정성 (11-13, 로컬 1회)

로컬 DB에서 지도 판 5에 자리가 있는 곡 248곡(가수 55명, 구역 18개)으로 40곡짜리 두 사람을 만들고, 각자 5·10·20곡만 무작위로 30번 뽑아 점수를 계산했다(`CodeMatchScoreTest`, 시드 42). 실제 사용자 데이터가 아니라 곡 풀이 작고 한국 팝에 쏠려 있다.

| 경우 (40곡 전체 점수) | 5곡: 평균 ± 표준편차 (최소~최대) | 10곡 | 20곡 |
|---|---|---|---|
| 무작위 두 사람 (78) | 42.5 ± 15.1 (8~82) | 59.4 ± 10.9 (36~81) | 70.0 ± 4.3 (62~77) |
| 구역이 갈린 두 사람 (21) | 10.1 ± 5.9 (0~19) | 14.6 ± 4.3 (3~20) | 17.4 ± 2.5 (13~21) |
| 취향이 겹치는 두 사람 (87) | 50.5 ± 17.3 (19~83) | 67.4 ± 14.6 (5~87) | 77.8 ± 6.9 (58~88) |

- **곡이 적으면 흔들릴 뿐 아니라 점수가 낮게 치우친다.** 5곡은 전체 점수보다 평균 35점가량 낮고(무작위·겹침), 10곡도 19~20점 낮다. 겹친 가수·노래와 장르 코사인이 곡 수가 늘수록 커지는 항목이기 때문이다. 5곡에서 겹친 가수 0인 경우가 30%(무작위)였다.
- 20곡이면 표준편차 2.5~6.9, 전체와 차이 3.6~9.3점으로 안정된다.
- 신뢰도 단계(5~9 낮음·10~19 보통·20~ 높음)는 측정과 맞는다. 다만 "낮음"에서는 점수 자체가 과소평가되므로, 비중 조정이나 곡 수 보정(예: 겹침 항목을 적은 쪽 곡 수로 보정, 5~9곡은 점수 대신 "아직 알아보는 중" 표시)은 사용자와 정한다.

## 12. ⑥ 관리자 구현 기록 (11-13)

- 결정(11-13, 관리 세션 전달): 비밀번호·OTP 실패 합쳐 5번이면 15분 잠금 + IP 분당 제한, 감사 기록 1년 보관(방 완전 삭제 때 그 방 기록의 IP·닉네임은 지움), TOTP 비밀값은 `ADMIN_TOTP_KEY`로 AES-GCM, 계정은 `tools/admin-account`로 사용자가 직접 만듦, ⑤ 응답에 `sharedArtistCount`·`sharedGalaxyCount` 추가.
- 마이그레이션 `V26__admin.sql`: admin_user·admin_session, `room.status_before_hide`, audit_log 참조 SET NULL + 색인, 기존 기록 detail에 방 공개 id·이름 채움.
- core: `admin/AdminRepository`(계정·세션·방 목록·상세·감사 기록 조회), `RoomRepository.audit`가 detail에 `roomPublicId`·`roomName`을 자동으로 붙임(방 없는 기록 가능), `hide`가 숨기기 전 상태 저장, `restore`, `purgeHidden`이 먼저 그 방 기록의 `ip`·`nickname`·`oldNickname`·`newNickname`을 지움, `deleteOldAudit`.
- api `admin/`:
  - `AdminSecurity.kt`: TOTP(RFC 6238, SHA1·30초·6자리, 앞뒤 1칸, 같은 칸 재사용 거절), Base32, `TotpCipher`(AES-256-GCM, iv 12바이트 + 암호문·태그 base64), 쿠키 `gyeol_admin`(HttpOnly·SameSite=Strict·Path=/api/admin, Secure는 설정값), `@AdminOnly AdminContext`, `/api/admin/**`에 `Cache-Control: no-store`와 상태 변경 요청의 Origin 확인.
  - `AdminController.kt`: `POST /api/admin/login`(→ challenge) · `POST /api/admin/login/otp`(→ 쿠키) · `POST /api/admin/logout` · `GET /api/admin/me` · `GET /api/admin/rooms?q=&status=&sort=created|active|songs&page=&size=` · `GET /api/admin/rooms/{rid}`(방장 이메일 `a***@g***.com`) · `POST …/hide {reason}` · `POST …/restore` · `PATCH /api/admin/rooms/{rid} {name}` · `PATCH …/members/{mid} {nickname}` · `DELETE …/members/{mid} {reason}`(방장 409) · `GET /api/admin/audit?room=&page=&size=`. 로그인 실패 기록이 401과 함께 되돌려지지 않게 로그인은 트랜잭션으로 묶지 않음. 키가 비어 있으면 503 `ADMIN_DISABLED`.
- worker: 정리 작업에 1년 지난 감사 기록 삭제.
- `tools/admin-account/make_admin.py`(uv): 사용자가 직접 실행. BCrypt(12)·OTP 비밀값·otpauth 주소·INSERT 문을 화면에만 출력. `--self-test`는 가짜 값으로 TOTP(RFC 벡터)·암호화 왕복·BCrypt만 확인(세션은 이것만 실행).
- 로컬 전용: `application-local.yml`의 `gyeol.admin.totp-key`는 가짜 키, `local/admin-dev-seed.sql`은 가짜 계정(admin@local.test / local-admin-only / OTP 비밀값 JBSWY3DPEHPK3PXP). 운영에는 쓰지 않는다.
- 운영 계정 만들기 절차: ① 운영 키 `ADMIN_TOTP_KEY`(base64 32바이트, 예: `openssl rand -base64 32`)를 비밀 저장소에 두고 API에 환경 변수로 준다. ② 사용자 자신의 터미널에서 `ADMIN_TOTP_KEY=… uv run --directory tools/admin-account python make_admin.py`. ③ 출력된 otpauth 주소를 인증 앱에 등록하고, INSERT 문을 운영 DB에서 실행한다. 출력은 저장하지 않는다. ④ 배포 때 Traefik IP 허용으로 `/api/admin`과 웹 `/admin`을 지정 IP만 연다(Helm 값).
- 테스트: api `AdminSecurityTest` 4개(RFC 6238 벡터, 시간 칸, Python 도구 암호문 풀기, 이메일 가리기), `RoomApiIntegrationTest` 관리자 3개(로그인 2단계·재사용·로그아웃, 잠금 15분·실패 기록, 방 관리·다른 출처 403·감사 기록·완전 삭제 뒤 IP·닉네임 지움). 전체 api 46(측정 1개 건너뜀)·worker 72 통과.
- 로컬 확인: 개발 서버에서 V26 적용, 로컬 시드 계정으로 로그인 2단계 → 방 목록 조회.
- 11-13 I-19 결정: 관리자 방 상세에서 최근 담긴 노래(`recentSongs`)를 뺐다. 관리자는 방 이름·참가자·곡 수·감사 기록만 본다(관련 SQL `AdminRepository.recentSongs` 삭제).

## 13. I-30 가수 화면 구현 기록 (11-14, 설계 `artist-search.md`, 결정 A1~A9)

### ① 가수 묶기·같은 곡 합치기·가수 카드
- `api/search/TrackMerger`: 곡 고르기 검색(`/api/tracks/search`)과 방 검색이 함께 쓴다.
  - Last.fm 가수 이름(정규화)이 우리 가수 이름·별칭과 맞으면 그 가수로 바꿔 쓴다. 여럿이 맞으면 대표 이름이 같은 가수 하나 → 들린 곡이 있는 가수 하나 순으로 고르고, 그래도 못 고르면 그대로 둔다.
  - 같은 곡: ① 녹음 MBID ② 식별 캐시 `title_alias`("제목|가수 - Topic") ③ 같은 가수이고 제목 표기가 겹침. 표기는 전체, 괄호 앞·안("A (B)"), 한글·로마자로 한 번 나뉜 두 쪽("NAN CHUN 난춘"). 괄호 안이 판 표시(Inst.·Live·Remix·ver.·feat. 등)면 나누지 않는다.
  - 같은 곡이면 DB 곡을 남기고, Last.fm 곡이 DB 곡으로 바뀐다(`trackId`·표지).
  - 결과 안에서 같은 가수는 한 표기로 맞춘다(가장 많이 나온 표기).
- `TrackItem.artistId` 추가(우리 가수 id, 없으면 null).
- 방 검색 응답 `artist`(가수 카드, 없으면 null): `{artistId, name, aliases, songCountInRoom, onMap}`. 판정 순서는 아래와 같다.
  1. 검색어가 우리 가수 한 명의 이름·별칭과 같음
  2. 결과 절반 이상이 한 가수이고 검색어가 그 이름의 앞부분
  3. 우리 DB에 없으면 Last.fm 결과 절반 이상의 가수 이름이 검색어와 같음(`artistId: null`)
  - 설계 2-3의 Last.fm `artist.getInfo` 호출 대신 3번 규칙을 썼다(검색마다 외부 호출 1번을 아끼려고).
- core `TrackSearchRepository`: `DbTrackHit`에 `artistId`·`mbTitle`, `hits(ids)`·`hitsOfArtist`·`trackIdsByMbids`·`aliasTracks`·`artistsByNames`·`artistNames`.
- 테스트:
  - api `TrackMergerFormsTest` 3개
  - `RoomApiIntegrationTest` I-30 ① 1개: 다비치·DAVICHI 한 가수, 8282 한 줄, Every Moment Of You·너의 모든 순간(Sung Si Kyung)이 DB 곡 하나로, 같은 표기, 가수 카드
- 로컬 실제 Last.fm 확인:
  - "다비치": 카드 DAVICHI(별칭 다비치·Davichi), 10곡 모두 DAVICHI 표기
  - "너의 모든 순간": 성시경 DB 곡 1줄, "Every Moment of You 너의 모든 순간"(Sung Si Kyung)은 합쳐짐, "- Piano Ver."는 다른 곡으로 남음

### ② 가수 화면 인기순
- `GET /api/rooms/{rid}/artists/{artistId}/songs?sort=popular&page=N`(우리 가수), `GET /api/rooms/{rid}/artist-songs?name=&page=N`(카드 `artistId: null`인 가수, 이름이 우리 가수 한 명과 맞으면 그 가수로).
- 응답 `{artist:{artistId,name,aliases}, sort, page, pageSize:20, hasMore, items:[{item:TrackItem, mine, sameSong:[닉네임], rank, releaseDate}], scan}`. 20곡씩, 최대 5쪽(100곡, A6). 정렬값이 틀리면 400, 없는 가수 404.
- `api/room/ArtistSongsService`: Last.fm 이름 후보(대표 표기 + 한글 별칭 하나 + 로마자 별칭 하나, 정규화가 다른 것만 최대 3개, A2)마다 50곡씩 2쪽을 받는다. 같은 곡(제목 표기·MBID)은 재생 수를 더해 한 줄로 합치고 재생 수 순으로 정렬한다. 우리 곡과 맞추는 순서는 MBID → 식별 캐시 → 같은 가수 곡의 제목 표기. 합친 목록은 10분 메모리 캐시라 쪽을 넘길 때는 Last.fm을 다시 부르지 않는다.
- fetcher `/internal/lastfm/top-tracks`: `page`(1~20), `limit` 최대 50, 항목에 `mbid`·`playcount`·`listeners`, 응답에 `total`. 추천(worker)의 기존 호출은 그대로 동작.
- 테스트: `RoomApiIntegrationTest` I-30 ② 1개(가짜 Last.fm: 한글·영문 페이지 합치기, 재생 수 합산 순서, 20+12곡 두 쪽, 우리 곡 맞춤, 친구·담음, 이름으로, 400·404).
- 로컬 실제 Last.fm(DAVICHI, artistId 2): 이름 2개(DAVICHI·다비치)에서 142곡 → 100곡, 우리 곡 18개 맞춤. 첫 쪽 1.9초(Last.fm 4번), 다음 쪽 20ms(캐시).
- 남는 한계: 영문 제목만 있는 곡(예: "This love")은 같은 노래의 한글 제목과 근거가 없으면 합치지 않는다(A3).

### ④ 지도 행성 → 가수 화면
- 백엔드 변경 없음: 행성 자세히(`GET /api/rooms/{rid}/artists/{artistId}`)와 가수 화면이 같은 `artistId`를 쓴다. 웹에서 연결만 하면 된다.

### ③ 가수 화면 최신순 (V27)
- `V27__artist_releases.sql`(전역): `artist_release_scan`(QUEUED·RUNNING·DONE·FAILED, 녹음 수·저장 수), `artist_recording`(가수 × 정규화 제목 하나 = 가장 이른 `first_release_date`, `is_version`).
- 흐름:
  1. `GET …/artists/{artistId}/songs?sort=latest`가 처음이거나, 끝난 지 7일(`gyeol.artist.scan-refresh`, `ARTIST_SCAN_REFRESH`, A8)이 지났거나, 실패했으면 QUEUED로 바꾸고 room-song 큐에 `SCAN_ARTIST_RELEASES`를 보낸다(A5). QUEUED·RUNNING이면 다시 보내지 않는다.
  2. worker `artist/ArtistReleaseScanner`가 fetcher `/internal/musicbrainz/artist-recordings`(MusicBrainz browse, 100개씩, 초당 1회 제한 공유)로 훑는다.
     - 영상 녹음은 뺀다.
     - 같은 제목은 날짜가 이른 쪽만 남긴다.
     - 판 표시(공용 `TitleVersion`)는 `is_version`으로 표시한다.
     - 쪽마다 바로 저장하므로 RUNNING 중에도 저장된 것이 보인다.
     - 한 가수 최대 3,000녹음.
- 응답: 인기순과 같은 모양 + `scan`(RUNNING·DONE·FAILED), `items[].releaseDate`(YYYY·YYYY-MM·YYYY-MM-DD, 없으면 null → "발매일 모름"). 날짜 내림차순, 날짜 없는 곡 맨 뒤, 판 표시 곡 숨김(A7). 같은 녹음이 우리 DB에 있으면 `trackId`·표지. 우리 DB에 없는 가수(이름만)는 400.
- 테스트:
  - worker `RoomSongIntegrationTest` I-30 1개: 가짜 250녹음 3쪽 → 248곡, 같은 제목 가장 이른 날, 영상 빼기, 판 표시, 요청 없으면 무시, 다시 모아도 같음, 실패 FAILED·다시 요청 가능
  - api I-30 ③ 1개: 요청 메시지 1번, 모으는 중 저장분만, 20+7 두 쪽, 순서·날짜 없음 맨 뒤·판 숨김, 7일 뒤 다시, 이름만 400
- 로컬 실제 MusicBrainz:
  - DAVICHI: 녹음 191개 → 곡 171개(판 표시 39), 1.9초. 날짜 있음 171/171(일 단위 170)
  - 성시경: 녹음 217개 → 192곡, 2.3초. 날짜 192/192
  - 설계 1-3의 검색 기반 55%보다 가수 녹음 목록 쪽이 훨씬 잘 채워져 있다(표본 2명).

### I-36 인기순 잡음 곡 거르기 (11-14)
- `api/search/TitleNoise`: 인기순에서 원곡이 아닌 Last.fm 항목을 뺀다. 대상은 직캠·팬캠·fullcam·4K, "cover by"·"(cover)"·커버, 1시간·1 hour, "/ 가사 Lyrics"·"(lyrics)"·lyrics video, Performance Video·Dance Practice·안무 영상·교차편집·리액션, M/V·official (music) video, "MBC 250129 방송"처럼 방송 녹화.
- 보수적으로 잡는다. 리믹스·밴드 버전·Inst.·Live 같은 실제 음원 판과, 제목일 수 있는 단어("Cover Me", "Lyrics", "Reaction Time Song", "Performance")는 거르지 않는다.
- 쪽 넘김 판단(50곡이면 2쪽 받기)은 거르기 전 개수로 한다.
- 테스트: `TitleNoiseTest` 2개(실제 표본 16개 거름, 정상 19개 남김).
- 로컬 실제 Last.fm: NewJeans 100곡·IVE 99곡에 잡음 0. 전에는 NewJeans에서 직캠·cover by·1시간 가사·Performance Video·방송 녹화가 보였다.
- 인기순 줄의 연도는 우리 DB 곡(trackId 있음)만 나온다(그대로 둠).

