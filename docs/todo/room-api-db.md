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

### I-31 방에 바로 담은 DB 곡의 표지 확인 (11-14)
- 원인: 표지 확인(cover 큐)은 식별 경로(`IdentifyService.requestCovers`)에서만 요청됐다. 방에 DB 곡을 바로 담는 경로(201)와 예전 데이터는 `cover_checked_at`이 비어 있었다(로컬 323곡 중 41곡, 예: track 316 "넌 감동이었어").
- 고침:
  1. api 바로 담기에서 확인 전 곡이면 room-song 큐에 `COVER {trackIds}`를 보내고, worker가 `requestCovers`(확인 안 한 곡만 cover 큐로)를 부른다.
  2. worker `identify/CoverBackfill`: cover 역할 worker가 켜질 때 한 번, 확인 안 한 곡(최대 2,000)을 cover 큐로 보낸다. 이미 확인한 곡은 고르지 않아 여러 번 켜도 같다. `gyeol.worker.cover.backfill-on-start=false`로 끈다.
- 테스트: api I-31 1개(확인 전 곡만 COVER), worker I-31 1개(COVER 메시지 → 확인 안 한 곡만 cover 큐).
- 로컬: worker 재시작(02:32) 때 41곡을 보냈고 몇 분 안에 미확인 0. 표지 있는 곡은 306/323(나머지 17곡은 Cover Art Archive에 표지가 없는 곡), track 316도 표지가 생김.

### I-34 설명·근거 조각의 대괄호 숫자 (11-14)
- 조사(사실, 로컬):
  - 가수 설명 1,416개 중 163개 끝에 "[564][565]"가 있었다. 모두 **LLM이 근거 조각 번호를 본문에 적은 것**이다. 위키 각주가 아니고, 번호가 그 설명의 evidence와 같다.
  - 위키 각주 "[1][2]"는 Last.fm 소개글 근거 조각 1,455개 중 9개에 있었다. 위키백과 조각에는 0개.
  - 은하 이유(107)·코드 맞추기 이유(4)·곡 설명(108)에는 없음.
- 고침(LLM 다시 부르지 않음):
  - core `knowledge/Citations`: `stripEvidenceNumbers`(설명 본문의 [숫자] 모두), `stripFootnotes`(1~3자리 [숫자]). 문장부호 앞 공백도 정리한다.
  - `MapRepository.saveDescription`은 설명을 저장할 때 근거 번호를 지운다. 번호는 evidence 칸에 그대로 있다.
  - `KnowledgeRepository.saveEntity`는 근거 조각을 저장할 때 각주를 지운다.
  - `V28__strip_citations.sql`: 기존 데이터를 같은 규칙으로 정리한다(설명 163 → 0, 조각 9 → 0, 로컬 02:39 적용).
- 남는 것: 설명 벡터는 대괄호가 있던 문장으로 만들어졌다. 차이가 숫자뿐이라 다시 만들지 않는다.
- 테스트: `CitationsTest` 2개, `RoomApiIntegrationTest` I-34 1개(저장할 때 지움, evidence 유지).

### I-41 인기순·최신순 잡음 두 단계 거르기 + I-39 일부 (11-14)
- 원칙: 화면 요청 때 MusicBrainz를 부르지 않는다. MusicBrainz는 worker가 가수 단위로 한 번 모은 `artist_recording`(V27)만 쓴다.
- 1차 이름 규칙(core `identify/TitleNoise`, api에서 옮김): 녹음 목록 유무와 상관없이 항상 적용한다.
  - 직캠·커버(괄호 속 cover, "… Cover 이름", acoustic/piano 등 cover)·1시간·가사·퍼포먼스·안무·M/V·방송 녹화
  - 반주(instrumental·inst·MR)
  - 팬 편집(flip·bootleg·jersey club·future funk·areia·kpop remix·장르 리믹스)
  - 시상식 무대판(MMA·KGMA ver/remix)·Cut Ver.·데모곡
  - VCR·stage talk·"[MINJI] …" 멤버 스킷
  - 예능(파이프 두 개 이상·"@ 방송")·이모지·이상한 기호 3개 이상
  - 뉴스 문장(전속계약·속보·[단독]·기자회견)
  - Last.fm 결과의 가수 이름이 이 가수가 아닌 곡
- 2차 녹음 목록 대조(api `ArtistSongsService`, 수집이 DONE일 때만):
  - 녹음 제목(판 표시 포함)과 표기가 맞는 곡은 남긴다. 언어판(-Japanese version-·- English Version)과 (feat. …)는 떼고 원곡 제목으로도 맞춘다. 리믹스·밴드 버전은 떼지 않는다.
  - 안 맞는 곡은 청취자 수가 1위의 1% 이상일 때만 남긴다.
  - 제목에 가수 이름이 든 영상식 제목("NewJeans (뉴진스) 'Zero' Official")은 녹음 목록에 없으면 뺀다.
  - 녹음 목록이 없거나 FAILED면 수집만 요청하고 1차만 적용한다.
  - 캐시 키에 수집 끝난 시각을 넣어, 다 모이면 다음 요청부터 2차가 반영된다(worker가 api 캐시를 비울 수 없어서 이 방식).
- 최신순 수집(worker `ArtistReleaseScanner`, I-39): fetcher `artist-recordings`에 `lengthMs`·`primaryArtistMbid`·`credit`(inc=artist-credits)를 더했다. 아래는 저장하지 않는다.
  - 주 가수(첫 크레딧)가 이 가수가 아닌 녹음: IVE "Graue Herren"(Newport, CAMELMOB & IVE), YOASOBI "the NOISE"
  - 60초 미만: "IVE on Thank U" 등 해설, YOASOBI Prologue
  - 1차 이름 규칙에 걸리는 것과 라이브 공연 사이 부분(제목 전체가 opening·ending·band session·mc·talk): YOASOBI "Band Session"·"Opening"
  - `V29__rescan_artist_releases.sql`로 기존 수집을 비워 새 규칙으로 다시 모은다.
- 검색: Last.fm 결과의 가수 이름이 영상 제목 같으면 뺀다(`TitleNoise.isVideoLikeArtist`: 한글 조사 뒤 띄어쓰기가 있는 12자 이상, 파이프·@·이모지). "성시경(Sungsikyung)의 킬링보이스를 라이브로!"는 빠지고 "P!nk"·"Clover!"는 남는다.
- 로컬 실측(11-14, 인기순 5쪽 곡 수):

| 가수 | I-36 뒤(전) | 녹음 목록 없음(1차만) | 녹음 목록 있음(1·2차) |
|---|---|---|---|
| NewJeans | 100 | 53 | 27 |
| DAVICHI | 100 | 100 | 100 |
| 성시경 | 95 | 95 | 95 |
| YOASOBI | 100 | 100 | 81 |
| IVE | 99 | 94 | 92 |

  - NewJeans 수집 실패(FAILED, worker를 끄고 측정)는 53곡으로, 없음과 같은 목록이다.
  - NewJeans 있음 27곡에 남은 잡음은 0이다. 원곡 18곡 + 공식 리믹스(250·FRNK·J.I.D) 8곡 + "Gods".
  - NewJeans 없음·실패 53곡에 남은 잡음은 분명한 것 약 12곡이다(Celebrity·Odoriko·인트로·하입보이·어텐션·"Intro + Attention + Hype boy"·팬 리믹스 몇 개·"아름다워 ( LG Gram )" 등). Band ver.·Performance ver. 8곡은 판단이 애매해 남긴다.
  - YOASOBI에서 빠진 19곡은 로마자 제목 중복(Yoru ni Kakeru → 夜に駆ける는 남음), THE FIRST TAKE, 다른 가수 곡 커버("/ 初音ミク")다. 정상 곡 손실은 "Racing into the Night"(영문판) 1곡이다.
  - IVE에서 빠진 것은 "About …" 해설 트랙·"Performance Stage 가로 ver." 등이다. 일본어판은 원곡 대조로 남는다.
- 테스트:
  - `TitleNoiseTest` 4개(실제 잡음 40여 개 거름, 정상 27개 남김, 라이브 사이 부분·영상 제목 가수, 가수 이름 든 제목)
  - `ArtistMatchFormsTest` 1개
  - `RoomApiIntegrationTest` I-41 1개(없음 → 1차만·수집 요청, 모으는 중 대조 안 함, 다 모이면 1% 규칙, 실패 → 1차만·재요청), 검색 영상 제목 가수
  - worker `RoomSongIntegrationTest`(주 가수 다름·60초 미만·라이브 사이·팬 편집 거름, 길이 모름은 남김)


### I-40 흐린 행성 수 (11-14)
- 결정(관리 세션 11-14): 행성 1개면 3명. 가수 판 "가까운 가수"와 같은 목록·순서에서 방 가수를 뺀 앞 3명이다. 행성이 늘면 행성마다 나누고, 전체 상한은 6명이다. 방에 있는 가수와 중복은 뺀다. 응답에 `artistId`가 있다.
- 구현(api `RoomSongController.ghosts`):
  - 행성마다 `min(3, ceil(6 / 행성 수))`명씩 가까운 가수를 고른다(`nearestPlaced`, 설명 없는 가수는 원본 벡터).
  - 최근 행성부터 채우고 6명이 차면 멈춘다. 앞에서 고른 흐린 행성은 다음 행성 조회에서 뺀다.
- 로컬 확인(11-14 02:59 api 재시작 뒤): 방 3곳(행성 37·23·9개)은 모두 흐린 행성 6명, 방 가수와 겹침 0, 중복 0이다. 임시 기기 열쇠로 조회하고 바로 지웠다. 행성 1개인 방은 로컬에 없어 테스트로만 확인했다.
- 테스트: `RoomApiIntegrationTest` I-40 1개. 행성 1개 → 가수 판 목록 앞 3명, 행성 2개 → 행성마다 3명, 방에 넣은 가수는 빠지고 지우면 다시 나옴. 도움 함수 `MapTestVectors.vecOf`. api 테스트 69개 통과(건너뜀 1).

### I-39 남은 것: 이름 주소의 최신순 400 (11-14)
- 설계 4-3대로, 최신순은 가수 id 주소(`/artists/{id}/songs?sort=latest`)로만 연다. 이름 주소(`/artist-songs?name=…&sort=latest`)는 이름이 우리 가수와 맞아도 400이다(전에는 200). 인기순은 이름 주소에서도 우리 가수로 바꿔 쓰는 동작이 그대로다.
- 로컬 확인(11-14 03:02 api 재시작 뒤): 수집 끝난 우리 가수 하나로 확인했다. 이름 주소 최신순은 400, id 주소 최신순은 200이다. 임시 기기 열쇠는 지웠다.
- 테스트: `RoomApiIntegrationTest` I-30 최신순에 "우리 가수 이름 + latest → 400" 한 줄을 더했다. api 테스트 69개 통과(건너뜀 1).

### I-44 가수 행성 얼굴 (11-14)
- 결정(관리 세션, 사용자 캔버스 승인): 지도 얼굴은 Commons 사진만 쓴다. 다음 순서다: 사진 → 방 곡 표지 → 색(장르 구역). 비용 0, LLM 없음.
- ① 표본(지도 가수 30명, fetcher 조회만): 사진 있음 19명(63%), 라이선스 CC BY 10·CC BY-SA 8·Public domain 1.
- ② API:
  - `planets[].face`·`ghosts[].face` = {kind PHOTO|COVER|NONE, url, galaxyId, author, license, pageUrl}. `ghosts[].galaxyId`도 더했다.
  - 가수 판에 `face`·`photo`, 가수 화면 `artist.photo`를 더했다.
  - 사진 주소 `GET /api/rooms/{rid}/artists/{id}/image`(방 열쇠, S3, private 1일)를 새로 만들었다(api `room/ArtistFaces.kt`).
  - COVER는 방에 가장 먼저 담긴 그 가수 곡의 표지다. 흐린 행성은 PHOTO 또는 NONE만 된다. YouTube 행은 지도에서 NONE으로 처리한다.
  - 지도 ETag는 `"{mapRev}-{Commons 수}-{마지막 확인}"`이다. 사진이 늘면 304 대신 새 응답이 간다.
- ③ 일괄 수집(worker):
  - `ArtistImageMessage.commonsOnly`를 더했다. Commons만 찾고 없으면 NONE이다. 가수 사이 `bulkPause` 2초로 MusicBrainz 줄을 곡 식별에 양보한다.
  - `POST /internal/artist-image/map-fill`(로컬 전용 `MAP_ADMIN_ENDPOINT=true`) 대상: 활성 지도 판 가수 중 미확인 + Commons 행은 있는데 S3 파일이 없는 가수.
  - 로컬 LocalStack이 11-13 15:23 다시 시작돼 `artists/` 파일이 사라졌다. 지도 가수 29명이 이 경우다. 지도 밖 세션 결과 가수 14명의 파일은 이번 범위 밖이다.
  - 11-14 09:52 worker를 재시작(MAP_ADMIN_ENDPOINT=true)하고 1,465명을 큐에 넣었다. 처음 20명은 COMMONS 12·NONE 8. 10명당 약 40~55초로, 끝은 11:30~11:45 예상이다.
- 테스트:
  - api `RoomApiIntegrationTest` I-44 1개. 덤으로 "담기" 테스트의 SQS 찾기를 여러 번 받게 고쳤고, I-40·I-44 끝에 활성 판을 끈다. api 70개 통과.
  - worker `ArtistImageBulkTest` 2개(commonsOnly는 YouTube 안 부름·없으면 NONE, 보통 메시지는 기존대로). worker 79개 통과.
- 작성자 정리(11-14, 관리 세션 요청): core `identify/CommonsAuthor.clean`을 새로 만들었다. HTML 태그·엔티티, 앞의 칸 이름("Upload:"·"Photo:"·"Uploaded by:" 등), 연속 공백을 지운다.
  - worker는 저장할 때, api는 `commonsPhotos`로 응답할 때 정리한다. 이미 저장된 행도 다시 받지 않고 응답에서 정리된다.
  - 실제 저장값 3개("Upload:  dramatized" → "dramatized", "Upload:  K-POPit 티비텐", "Republic of  Korea")를 응답에서 확인했다.
  - 테스트 api `room/CommonsAuthorTest` 1개. api·worker는 09:56:53에 재시작했고, 일괄 수집은 큐에 남은 메시지로 이어진다.
- 일괄 수집 결과(11-14 11:38:52 큐 비고 처리 중 0):
  - 활성 지도 판 가수 1,471명 중 미확인 0명이다. COMMONS 874(59%)·NONE 592·YOUTUBE 5다. 표 전체(지도 밖 포함)는 COMMONS 888·NONE 592·YOUTUBE 6이다.
  - 실패: DLQ 0, MusicBrainz 거절 0. worker ERROR 2건(10:40·11:01)은 fetcher의 Wikimedia 503(UPSTREAM_UNAVAILABLE)이다. 두 메시지 모두 600초 뒤 다시 받아 끝났다.
  - 방 8(jj7un3uFPx) 지도: 행성 37개 = PHOTO 31·COVER 6, 흐린 행성 6개 = PHOTO 5·NONE 1이다. PHOTO 사진 주소 36개가 모두 200이다(임시 기기 열쇠는 넣었다가 지웠다, 남은 행 0).
- 작성자 주소 줄이기(11-14, 관리 세션 요청): `CommonsAuthor.clean`이 작성자 안의 주소를 표시용 이름으로 바꾼다. YouTube 채널(`/@이름`·`/c/이름`·`/user/이름`)은 "@이름"이고, 그 밖은 도메인(www.·m. 뺀 호스트)이다. 텍스트와 섞여 있으면 주소 부분만 바꾼다. 원본 링크는 pageUrl에 남아 있다.
  - 테스트: api `CommonsAuthorTest`에 1개를 더했다(기존 YouTube /user 기대값을 "@x"로 바꿈). api 72개(건너뜀 1)·worker 79개 통과.
  - 로컬(11:46:12 api·worker 재시작 뒤): IVE(1810) 지도 face와 가수 판 photo의 author가 "@_TV10"이다. 주소가 든 COMMONS 작성자 18건 모두 주소가 이름·도메인으로 바뀐다. 다만 Commons 원문이 긴 설명인 몇 건("[facebook.com …" 위키 링크 괄호, 저장 시 잘린 "see http:/")은 문장이 그대로 남는다.

### I-47 긴 설명형 작성자 정리 (11-14)
- `CommonsAuthor.clean`에 네 단계를 더했다: ① 위키 링크 "[주소 텍스트]"·"[[문서|텍스트]]"는 텍스트만 ② 끝이 잘린 "http:/"·"h" 같은 주소 조각 제거 ③ ", see …"와 20자 넘는 괄호 설명(닫히지 않은 괄호는 끝까지, 중첩은 바깥 기준) 제거 ④ 40자(코드포인트) 넘으면 단어 경계에서 자르고 "…".
- 주소가 든 COMMONS 작성자 18건: 위키 링크·잘린 조각·see·긴 괄호가 남은 행은 0건이다. 예: "Danny Choo (site owner of …)" → "Danny Choo", "Erika_Rodriguez (Kika, … see http:/" → "Erika_Rodriguez".
- 40자 상한 때문에 "Korea.net / Korean Culture and Information Service (Jeon Han)"은 "Korea.net / Korean Culture and…"가 된다. 원본 링크는 pageUrl에 있다.
- 테스트: api `CommonsAuthorTest` 1개 추가(기존 40자 넘는 두 기대값 수정). api 73개(건너뜀 1)·worker 79개 통과.
- 로컬(11:50:24 api·worker 재시작 뒤): 방 8 PHOTO 36개 작성자 최대 36자, "["·"http" 0건이다. IVE는 "@_TV10" 그대로다.
- 짧은 괄호 남기기(11-14, 관리 세션 요청): 40자를 넘어 자를 때 끝에 20자 이하 괄호(촬영자 이름)가 있으면 괄호는 남기고 앞부분만 줄인다. 앞부분 몫은 40 − 괄호 길이(최소 20자)이고, 이때 전체 상한은 60자다. "Korea.net / Korean Culture and Information Service (Jeon Han)" → "Korea.net / Korean Culture… (Jeon Han)".
  - 테스트: api `CommonsAuthorTest` 1개 추가. api 74개(건너뜀 1)·worker 79개 통과. api·worker 11:53 재시작 뒤 방 8 가수 115 작성자가 위 값(38자)이다.
- 점검 수정 5건(11-14, 관리 세션 요청·사용자 승인), api `CommonsAuthorTest`에 5개 추가:
  1. 범위 밖 숫자 엔티티(`&#99999999;`)는 빈칸이다. 전에는 `Character.toChars` 예외로 지도 전체가 500이 될 수 있었다.
  2. flickr(photos·people)·instagram·x/twitter 프로필 주소는 경로의 사용자 이름이다("flickr.com/photos/jsmith" → "jsmith"). 숫자 id(12345@N00)나 게시물 주소(instagram /p/)는 도메인이다.
  3. 멱등: 이미 "…"로 줄인 앞부분과 41자 결과는 다시 자르지 않는다. 기존 테스트 입력 20개에서 clean(clean(x)) == clean(x)를 확인한다.
  4. 괄호를 빼서 칸이 비면 빼기 전 문자열에 상한만 적용한다.
  5. 끝 조각 지우기는 대소문자를 구분하고 "http:"·"https:/"·"www." 이상만 지운다. "h"·"ht"·"htt" 단독은 원문에 http 주소가 있을 때만 지운다("Park H"·"Park h"는 남음).

### I-45 YouTube 프로필 빼기 (11-14, 사용자 결정)
- worker: 가수·Topic 채널 모두 Commons가 없으면 NONE이다. YouTube 채널 프로필(`fetcher.channelImage`)을 부르지 않는다. fetcher 쪽 엔드포인트는 그대로다.
- api `StatsService` 가수 순위: artist_image·channel_image를 `source = 'COMMONS'`만 붙인다. imageSource는 COMMONS 또는 null이다.
- `V30__drop_youtube_artist_images.sql`: YOUTUBE 행을 지운다(artist_image 6·channel_image 26). NONE으로 바꾸지 않고 미확인으로 둔 이유는, NONE이면 30일 재확인 기한까지 Commons를 다시 보지 않기 때문이다. 다음 세션 계획·지도 일괄 채우기에서 Commons만 다시 찾는다. CHECK 제약의 'YOUTUBE'는 그대로 둔다.
- 테스트: worker `ArtistImageBulkTest` 보통 메시지 테스트를 I-45로 바꿨다(가수·채널 NONE, channelImage 안 부름). api `SessionApiIntegrationTest` 가수 순위 테스트에 YOUTUBE 행이 있어도 imageSource·imageUrl이 없음을 더했다.
- 결과: api 79개(건너뜀 1)·worker 79개 통과. 12:21:06 api·worker 재시작 때 V30이 적용됐다. 남은 행은 artist_image COMMONS 888·NONE 592, channel_image COMMONS 73·NONE 1이다.

### I-50 Commons 사진 2단계 경로 (11-14, 사용자 승인)
- fetcher `ArtistImageClient.commons`: P18이 없으면 Wikidata sitelinks의 kowiki → jawiki → enwiki 문서에서 pageprops `page_image_free`를 본다. 그 파일이 Commons에 있으면 기존 imageinfo·extmetadata 경로(작성자·라이선스·pageUrl, S3 저장)를 그대로 쓴다. source는 COMMONS이고, 응답에 `via`(P18·kowiki…)를 더했다.
- 거르기(`ArtistImageParser.rejectReason`):
  - LOGO: 파일명 logo·ロゴ·로고, .svg, Commons 분류에 logo.
  - NOT_SELF: 그래피티(러시아어 포함)·벽화·포스터·앨범 커버·서명.
  - 걸리면 다음 위키를 본다. 한 번 본 파일은 다시 조회하지 않고, 파일명만으로 걸리면 Commons를 부르지 않는다. 거른 파일은 fetcher 로그 "위키 대표 이미지 거름"에 남는다.
- worker `map-fill?retryNone=true`: 활성 지도 판의 NONE 가수도 다시 찾는다(`ArtistImageRepository.mapNoneTargets`).
- 테스트: fetcher `ArtistImageParserTest` 2개(위키 제목·page_image_free, 관리 세션 표본 파일명 거르기), worker `ArtistImageBulkTest` 1개(retryNone·중복 제거). fetcher 55·worker 80·api 79(건너뜀 1) 통과.
- 일괄(12:31~13:10, 597명): COMMONS 874 → 910(+36: enwiki 19·kowiki 10·jawiki 7), NONE 561, 미확인 0. DLQ 0, 오류 0.
  - 거른 가수 32명: LOGO 30(실제 로고 21·.svg 서명 4·자리표시 그림 5), NOT_SELF 2(벽화 1·서명 1).
- 사용자 결정 대기: 로고 사용 여부. 지금은 전부 뺀다.

### I-14 방 표지 우리 주소 (11-14, 사용자 승인)
- 응답에 `coverPath`(없으면 null)를 더했다. 기존 `coverUrl`은 그대로다.
  - 대상: 내 노래·담기·식별 요청의 song, 지도 위성, 가수 판 songs, 가수 화면 items[].coverPath.
  - 저장본(track.cover_key)이 있으면 `/api/rooms/{rid}/covers/{trackId}`다. 웹은 `coverPath ?? coverUrl`.
  - 행성 얼굴 COVER도 저장본이 있으면 이 주소다.
- `GET /api/rooms/{rid}/covers/{trackId}`(api `room/RoomCovers.kt`): 방 열쇠, 저장본 없으면 404 COVER_NOT_FOUND, private 1일. 우리 DB 곡이면 방 곡이 아니어도 준다(가수 화면은 DB의 어떤 가수 곡이든 나옴).
- core `RoomSongRepository`: MapSong·MySong에 coverStored, `coverKey`·`storedCovers`를 더했다.
- 로컬 수: 방 곡 108곡 중 저장본 106, 둘 다 없음 2, 미확인 0이다. LocalStack 초기화로 cover_key 306개 중 228개 파일이 S3에 없다(방 곡 63곡, 방 8 위성 51개 중 11개 404). 운영과 무관하고, 로컬 복구는 하지 않았다.
- 테스트: api `RoomApiIntegrationTest` I-14 1개. api 80(건너뜀 1)·worker 80 통과. api 13:59:00 재시작.
- 같은 날 api 멈춤(12:28~13:53): 실행 중 api의 jar를 다시 빌드해 NoClassDefFoundError가 났다. 코드 문제가 아니다. jar를 빌드할 때는 그 모듈을 먼저 끄고 빌드 뒤 바로 재시작한다.
- 로컬 파일 다시 받기(11-14, LocalStack 초기화 뒤 S3에 없던 파일):
  - 표지 228개: 끊긴 cover_key를 비우고 cover 큐로 다시 받았다.
  - 가수 사진 14개: Commons만 찾는 메시지로 다시 받았다.
  - Topic 채널 사진 73개(전부): 저장된 artist_mbid로 다시 받았다. worker `ChannelImageTarget.mbid`가 있으면 이름 검색을 건너뛴다(테스트 1개).
  - 14:09에 끝났고, 세 경로 모두 빠진 파일 0개다. DLQ 0, 오류 0. 방 8 지도의 표지 51개·사진 37개 주소가 모두 200이다.
- 지도 ETag(관리 세션 요청): `"{MAP_FORMAT}-{mapRev}-{Commons 사진 상태}-{방 곡 표지 저장본 수}"`다.
  - `MAP_FORMAT = "v2"`는 RoomSongController.kt 상수다. 지도 응답 필드를 바꾸면 올린다.
  - 표지 저장본 수를 더한 이유는 표지 저장이 map_rev를 올리지 않아 coverPath가 바뀌어도 304가 났기 때문이다.
  - ETag를 쓰는 다른 응답은 없다.
  - 테스트: api `RoomApiIntegrationTest` 1개. api 81(건너뜀 1)·worker 81 통과. api 14:10:02 재시작.

### I-48 YouTube 데이터 보관 기간: 29일 기준·채널 프로필 코드 삭제 (11-14, 사용자 승인)
- 점검 결과: YouTube API 값을 저장하는 곳은 `video_check`(영상 ID → categoryId) 하나다. 정리 작업이 하루 한 번(04:00)이라 30일 기준이면 행이 최대 약 31일 남았다.
- 4-1: 조회(`VideoCheckController`)와 정리(`CleanupService`)의 기준을 core `VideoCheckRepository.RETENTION` = **29일** 하나로 합쳤다. 정리가 하루 늦어도 30일을 넘지 않는다. 테스트: worker `CleanupIntegrationTest` 29일 경계 1개(조회·정리 둘 다).
- 4-3: 쓰이지 않는 채널 프로필 호출을 지웠다. fetcher `YouTubeClient.channelImage()`·`GET /internal/youtube/channel-image`·`DefaultAvatar.kt`(+`DefaultAvatarTest` 3개), worker `FetcherClient.channelImage()`(+`ArtistImageBulkTest`의 never 검증 2줄). 이제 YouTube API 호출은 videos.list categoryId 하나뿐이다. CHECK 제약의 'YOUTUBE' 값은 그대로 뒀다.
- 4-2(판정을 거친 제목의 전역 테이블 보관, III.E.4.h)는 관리 세션이 정책 원문을 확인한 뒤 정한다.
- 테스트: fetcher 52(전 55, DefaultAvatarTest 3개 삭제)·worker 82·api 81(건너뜀 1) 통과. 15:54:01 fetcher·worker·api 재시작.

### I-51 식별(202) 결과에 선물 상자 (11-14, 사용자 승인)
- 문제: 식별을 거쳐 담긴 곡이 상자를 만들어도 `GET /api/rooms/{rid}/song-requests/{id}`(RequestView)에 상자가 없어, 화면이 WAITING 알림을 띄우지 못했다.
- V31: `song_request.gift_box_id`(→ gift_box, ON DELETE SET NULL), `gift_status`(생긴 순간 상태). worker `RoomSongService.identifyRequest`가 `gifts.onSongAdded` 결과를 `setRequestGift`로 남긴다(방 행 잠금 트랜잭션 안).
- 응답: `RequestView.gift = {boxId, milestone, status}`(201 `AddSongResponse.gift`와 같은 `GiftNotice`). 상자가 생긴 요청만 값이 있고, 아니면 null. status는 생긴 순간 값(WAITING·READY)이다. 지금 상태는 `GET /me`의 선물 요약으로 본다. 상자가 지워지면 null.
- 한 번만 알림: boxId가 상자마다 하나이고 같은 요청을 여러 번 받아도 같으므로, 화면은 본 boxId를 기억해 한 번만 띄운다(201과 202 결과에 같은 상자가 함께 나오지 않는다. 담기 한 번에 상자는 최대 1개).
- 테스트: worker `RoomSongIntegrationTest`(50곡째 READY·요청 표시·재처리 그대로, 100곡째 WAITING, 상자 없는 요청 null), api `RoomApiIntegrationTest`(I-51 모양·같은 boxId·생긴 순간 상태·삭제 시 null, 기존 흐름 null). worker 83·api 82(건너뜀 1)·fetcher 52 통과. 16:32:49 api·worker 재시작.
- 참고: I-48 4-2를 구현하게 되면 그 마이그레이션은 V32가 된다.

### 방 노래 검색 더보기 (11-14, 사용자 승인)
- `GET /api/rooms/{rid}/search?q=…&page=1~5`(기본 1), 한 쪽 10곡, 최대 50곡. 응답에 `page`·`hasMore`를 더했다. 가수 카드(`artist`)는 1쪽에만.
- 1쪽은 지금 검색 그대로(limit 10). `hasMore`는 10곡이 꽉 찼으면 true다. Last.fm을 더 부르지 않으려고 추정으로 둔다. 그래서 드물게 2쪽이 비어 있을 수 있다.
- 2쪽 이후: 1쪽을 다시 만들고(Last.fm은 `q|10` 캐시), `TrackSearchController.find(q, 50)` 목록을 뒤에 붙인다. 기존 `TrackMerger.dedupe`를 전체에 한 번 더 적용해 쪽 사이 중복을 막고 쪽으로 자른다. 합치기 규칙은 그대로다.
- fetcher: `/internal/lastfm/search` limit 상한 20 → 50(`SearchController.LASTFM_MAX`). Last.fm에 보내는 limit은 `max(30, limit)`. 11-14 무료 호출 1번으로 track.search limit=50이 50개를 주는 것을 확인했다(itemsPerPage 50).
- Last.fm 추가 호출: 검색어마다 10분에 최대 1번(2쪽을 처음 열 때, 캐시 `q|50`). 3~5쪽은 0번.
- 테스트: api `RoomApiIntegrationTest` 더보기 1개(4쪽, 중복 없음, 마지막 쪽 hasMore=false, 1쪽 = page 없는 응답, page 0·6은 400, 5쪽은 빈 목록에 false), fetcher `LastFmClientTest` 1개(limit=50 요청, 51 거절). fetcher 53·worker 83·api 83(건너뜀 1) 통과. 17:08:51 api·fetcher 재시작.

### 방 검색 가수 카드 보강: displayName·face (11-14, 사용자 승인)
- `RoomSearchResponse.artist`(ArtistCard)에 두 필드를 더했다. 기존 필드(artistId·name·aliases·songCountInRoom·onMap)는 그대로다.
  - `displayName`: 우리 DB `artist.name`(지도 행성·가수 판과 같은 이름). DB에 없는 가수는 null.
  - `face`: 지도 `Face`와 같은 규칙과 같은 계산(가수 판 `artist()`와 같음). 사진이면 PHOTO(방 주소·저작자·라이선스·pageUrl), 아니면 방에 담긴 그 가수 곡 중 먼저 담긴 곡의 표지로 COVER(coverPath 우선), 둘 다 없으면 NONE. DB에 없는 가수는 `NONE`.
- `name`을 바꾸지 않은 이유: `name`은 검색 결과에 실제로 보이는 표기(예: YOUNHA)다. 웹은 이 값을 가수 화면 이름 주소(`artist-songs?name=`)와 aliases 계산에 쓴다. 바꾸면 이름 주소 판정과 결과 줄 표기가 어긋날 수 있다. 그래서 표시용 `displayName`을 따로 더했다.
- 효과: 웹이 `face.kind == "PHOTO"`일 때만 `/image`를 부르면 사진 없는 가수의 404 요청이 사라진다.
- 테스트: api `RoomApiIntegrationTest` 2개(지도+사진 가수: displayName·PHOTO·galaxyId·출처 / DB만 있는 가수: NONE→곡 담은 뒤 COVER). api 85(건너뜀 1) 통과. 17:12:23 api 재시작.
- **대체 얼굴 (11-14, 사용자 승인):** 카드만 한 단계 더 둔다. 사진 → 방 곡 표지 → **DB 저장 표지(그 가수 곡 중 `cover_key`가 있는 곡, `plays_total` 큰 곡, 같으면 id 작은 곡)** → NONE. 새 함수 `RoomSongRepository.artistStoredCoverTrack`을 쓰고, 외부 호출은 없다.
  - 표지 주소 `/api/rooms/{rid}/covers/{trackId}`는 I-14 때부터 방 곡으로 좁히지 않고, 방 열쇠가 있으면 우리 DB의 저장본 있는 어떤 곡이든 준다. 그래서 고칠 것이 없다.
  - 안전 근거: 응답은 앨범 표지(Cover Art Archive 공용 이미지) 바이트뿐이다. 누가 어느 방에 담았는지, 업로드 기록은 응답에도 조회 조건에도 없다. 드러나는 것은 "그 곡이 우리 DB에 있다"는 사실뿐인데, 이것은 검색·가수 화면에서 이미 보인다.
  - 지도 행성·흐린 행성·가수 판(`artist()`)은 `ArtistFaces.faces`를 함께 쓰지만, 대체 단계는 카드 호출 쪽에만 넣었다. 그래서 동작이 그대로다. 가수 화면 머리(`ArtistHeader.photo`)는 사진만 쓴다.
  - 테스트 1개를 더했다(저장본 없음 NONE → 저장본 있으면 COVER, 동점은 id 작은 곡, 저장본 없는 곡은 재생 수가 많아도 제외). api 86(건너뜀 1)·worker 83 통과. 17:32:48 api 재시작.
  - 로컬 확인(방 8, 임시 열쇠 넣고 바로 지움): Fin.K.L(artist 48, 방 곡 0, 사진 없음) → COVER `/covers/48`(Blue Rain, plays 160) 200. 유승준(DB 없음) → NONE.
- **가수 판에도 같은 대체 (11-14, 사용자 승인):** `artist()`(행성·흐린 행성을 눌러 여는 판)의 face도 사진 → 방 곡 표지 → DB 저장 표지 → NONE 순서다. 지도 응답(`map()`)의 행성 얼굴은 그대로다. 테스트 1개를 더했다(NONE → 저장본 생기면 COVER, 지도에 행성 없음). api 87(건너뜀 1) 통과. api를 다시 켰다.

### 검색 때 가수 미리 준비 (B안, 11-14, 사용자 승인)
- 설계: scratchpad 메모 `search-artist-prepare-design.md`. 1쪽 가수 카드가 `artistId == null`이면 키 `n:{정규화 이름}`, `face == NONE`이면 키 `id:{artistId}`로 room-song 큐에 `PREPARE_SEARCH_ARTIST`를 맡긴다. 응답은 기다리지 않는다.
- V32 `search_artist_prep`·`search_artist_prep_daily`. V23에 공용 지도용 `artist_prepare`가 이미 있어 이름을 바꿨다. I-48 제목 만료 메모(`docs/todo/youtube-derived-title-expiry.md`)는 V33으로 고쳤다가, V33을 생일 정리 마이그레이션이 쓰면서 V34로 다시 미뤘다.
  - 맡기기 규칙: 같은 키는 하루 1번, QUEUED·RUNNING이면 다시 맡기지 않는다(1시간 넘게 멈추면 다시). 방당 하루 30명(`PREPARE_PER_ROOM`)까지.
- worker `ArtistPrepareService`
  - n: Last.fm 인기곡(6) → `find-artist`(이름·별칭 + 제목 3개 검증, 하나로 줄지 않으면 SKIPPED AMBIGUOUS) → 인기곡을 `"{이름} - Topic"` 키로 **`identifyTargets(allowLlm=false)`** 식별, 확정 mbid의 곡만 3곡 → `requestCovers` → Commons 사진(commonsOnly). 곡 사이 2초 쉼.
  - id: 표지 미확인 곡 3곡 + 사진을 다시 볼 때가 됐으면 사진 큐.
  - `start()`가 QUEUED일 때만 시작해 같은 메시지를 두 번 받아도 한 번만 한다. 공용 지도(PREPARE_ARTIST)에는 올리지 않는다.
- `IdentifyService.identifyTargets(…, allowLlm)`: false면 LLM 제목 정리와 다른 표기 묻기를 건너뛰고, 못 찾은 곡은 캐시에 남기지 않는다.
- 테스트
  - worker `ArtistPrepareIntegrationTest` 5개: 처음 보는 가수, 동명이인, 다른 가수 곡 버림, id: 키, 중복 메시지. 모두 LLM 호출 수 0을 확인한다.
  - api 3개: 연타해도 1번, 얼굴 있으면 안 맡김/NONE이면 id:, 다시 맡기기 조건과 방당 상한.
  - 결과: worker 88·api 90(건너뜀 1) 통과. 17:53:26 api·worker 재시작.
- 로컬 확인(방 8, 임시 열쇠는 넣었다가 지움)
  - 유승준: 첫 검색 카드 artistId null·NONE → 17초 뒤 DONE(가수 1854) → 다시 검색하면 카드 PHOTO(MBC PD수첩, CC BY 3.0)이고 노래 줄 가위·나나나·사랑해 누나에 trackId·표지가 붙는다.
  - 비: **한 글자라 검색 자체가 400**(검색어 2~100자)이다. 그래서 큐에 직접 넣어 확인했다. 10초 뒤 DONE(가수 1857 RAIN, 별칭 비·Rain) → "Rain" 검색에서 DB 카드(PHOTO, TV10 CC BY 4.0)가 나오고 Rainism·Hip Song·Only You 줄에 표지가 붙는다. 준비 전 "Rain" 검색은 카드가 없었다.
  - `llm_call` 1666 → 1666(유료 0). 공용 지도 준비(`artist_prepare`)는 0행이다.

### 내 노래 정렬 recent·genre·artist (11-14, 사용자 승인)
- `GET /api/rooms/{rid}/me/songs?sort=recent|genre|artist&cursor=&limit=&snapshot=`. 기본 recent는 지금과 같다(`snapshot` null).
- genre·artist는 첫 쪽에서 `snapshot`(그때까지 담은 곡의 마지막 id)을 돌려준다. 다음 쪽에 함께 보내면 그 이하 곡만으로 순서를 만든다. 순서가 고정돼 쪽 사이 중복·누락이 없다. 커서는 앞 쪽 마지막 곡 id다. 커서 곡을 그사이 뺐으면 409 `CURSOR_STALE`(처음부터 다시).
- 묶음 순서: 곡 많은 순 → 이름 순, "분류 중"(활성 지도에 장르 구역 없음)은 맨 뒤. 묶음 안은 최근 담은 순이다.
- 곡마다 `group = {key, kind(GENRE|ARTIST), name, count(쪽과 상관없는 전체 수), galaxyId?, artistId?, displayName?, face?}`.
  - artist의 face는 사진 → 방 곡 표지 → DB 저장 표지 → NONE이고, 그 쪽에 나온 가수만 계산한다.
  - genre 묶음 이름은 지도 구역 이름이고, 이름이 비어 있으면 "이름 없는 구역"이다.
- 테스트: api 1개(recent 호환, genre·artist 순서, groupCount, 분류 중 맨 뒤, 쪽 3곡씩 = 한 번에, snapshot 뒤 담은 곡은 안 섞임, 잘못된 sort 400). api 91(건너뜀 1) 통과. 18:00:00 api 재시작.

### 방 검색 한 글자 허용 (11-14, 사용자 승인)
- 방 검색(`/api/rooms/{rid}/search`)만, 공백을 뺀 검색어가 **한글·한자·히라가나·가타카나 한 글자**면 허용한다(`SearchText.isSingleCjk`). 라틴·숫자·기호 한 글자는 400이다. 2~100자 규칙은 그대로다.
- 이름·별칭이 정확히 그 글자인 우리 가수(`resolveArtists`)가 있으면 카드로 나오고, 그 가수 곡을 결과 앞에 둔다(1쪽과 2쪽 이후 모두, 그 밖의 순서는 그대로).
- fetcher `/internal/lastfm/search`도 같은 한 글자를 받는다(MusicBrainz 검색은 그대로 2자 이상). 그래서 한 글자 이름도 Last.fm 결과로 카드가 생기면 미리 준비(`n:{글자}`)를 맡길 수 있다. find-artist 확신 규칙은 그대로다.
- 곡 고르기 입구 `/api/tracks/search`는 바꾸지 않았다(같은 `find`를 쓰지만 `allowSingleCjk=false`).
- 로컬(방 8)
  - "비": 200, 카드 RAIN(1857) PHOTO, Rainism·Hip Song·Only You가 맨 앞이고 그 뒤에 "비밀번호 486", 다비치 곡이 이어진다(다비치 별칭·표기에 '비'가 들어 있다). source db, hasMore true.
  - "별": 200, 카드 없음(Last.fm 결과가 여러 가수의 "별"이라 가수가 한 명으로 모이지 않음), 미리 준비 맡기지 않음.
  - "a": 400.
- 테스트: api 1개("비" 200 + 카드 + 그 가수 곡 앞, "a"·"7" 400, "별" 200, 앞뒤 공백, 곡 고르기 입구 "비" 400), fetcher 테스트에 한 글자 3줄. fetcher 53·worker 88·api 92(건너뜀 1) 통과. 18:07:16 세 모듈 재시작.

### 가수 판 설명 구분·비슷한 아티스트 얼굴·곡 고르기 한 글자 (11-14, 사용자 승인)
- **사실 줄이 생기는 곳:** fetcher `knowledge/KnowledgeService.artist()`가 MusicBrainz `/artist/{mbid}`로 `mbSentence`를 만든다. "가수 'X' (MusicBrainz): 유형 …; 지역 …; 활동 시작 …; 장르·태그 …." 이 줄은 `knowledge_chunk`(source MUSICBRAINZ)에 저장된다.
  - 이 줄이 설명이 되는 경우: `MapLlm` fake(`LLM_MODE=fake`)의 `describeArtist`가 첫 근거 조각을 120자로 잘라 그대로 설명으로 저장한다(model "fake"). 로컬 15명이 이렇다. Bedrock 설명(1,457명)에는 이 모양이 없다.
- **응답:** `ArtistView.descriptionKind`는 `TEXT` | `FACTS` | null(설명 없음)이다. FACTS일 때 `facts = {area?, tags?}`를 준다.
  - area: 영어 나라 이름 → 한국어(South Korea → "한국", 나라가 아니면 null). tags: 상위 5개, 숫자가 든 세대 표시는 빼고 K-pop·R&B처럼 표기를 정리한다. 날짜는 넣지 않는다.
  - 사실은 저장된 MusicBrainz 조각(잘리지 않은 줄)에서 뽑고, 조각이 없으면 설명 문자열에서 뽑는다. `description` 문자열은 그대로다.
- **생일 문제:** MusicBrainz `life-span.begin`은 Person이면 출생일, Group이면 결성일이다. 이제 Person·Character는 begin을 넣지 않는다(fetcher 수정, 새로 받는 조각부터).
  - 이미 저장된 조각은 그대로다: 로컬 가수 조각 1,478개 중 681개에 "유형 Person … 활동 시작 {생일}"이 있다.
  - 이 조각이 `MapLlm.describeArtist`(Bedrock)의 근거 입력에 그대로 들어가므로, 기존 LLM 설명 입력에도 같은 잘못된 표시가 들어갔다. 다시 생성(유료)은 하지 않았다. 저장 조각을 SQL로 고치는 방안(무료)은 보고만 했다.
- **비슷한 아티스트(nearby):** 항목마다 `galaxyId`와 `face`(사진 → DB 저장 표지 → NONE, 외부 호출 없음).
- **곡 고르기 입구 `/api/tracks/search`:** 한글·한자·가나 한 글자 허용("비"·"별" 200, "a" 400). 웹 `web/src/pages/Pick.tsx:24`·`:76`의 `length < 2` 검사는 웹 세션이 고친다.
- 테스트
  - api: 판 FACTS 1개, nearby galaxyId·face와 TEXT 단언, 한 글자 테스트에서 곡 고르기 입구 200·400
  - fetcher: Group begin은 "활동 시작", Person은 넣지 않음
  - 결과: fetcher 54·worker 88·api 93(건너뜀 1) 통과. 18:13:48 api·fetcher 재시작.
- **저장 조각 바로잡기 (11-14, 사용자 승인, 로컬 DB):** `knowledge_chunk` 가수 MusicBrainz 조각 중 "유형 Person|Character" 줄의 `; 활동 시작 {날짜}`를 지웠다. 날짜 모를 "????-12-02" 10개도 포함해 **687행**을 바꿨다. Group 654행은 그대로다. 남은 Person 줄의 "활동 시작"은 0이다.
  - 벡터(`artist_vector` 원문)는 다시 만들지 않았다(다음 embed 때 반영). 로컬 fake 설명 15개 문자열에는 날짜가 남아 있지만, 판의 FACTS 표시는 조각에서 뽑아 날짜가 없다.
- **기존 Bedrock 설명 영향(읽기만):** 바로잡기 전 Person 조각에 생일이 있던 가수 중 Bedrock 설명이 있는 647명을 봤다.
  - 그중 **23명**이 생일 연도를 "{연도}년부터 활동을 시작" 같은 활동 시작 시점으로 썼다. 23명 모두 이 잘못이다.
  - 예: 김필 "1986년부터 음악 활동을 시작했다", 유라 "1992년부터 음악 활동을 시작했다", J. Cole "1985년부터 활동을 시작한 이후…", ちゃんみな "1998년부터 활동을 시작하여 2016년 인디 데뷔…", Nettspend "2007년부터 활동을 시작한…".
  - 다시 만들 경우 비용(Haiku 4.5, 평균 입력 1,392·출력 239 토큰, $1/$5 per MTok 가정): 23명은 약 $0.06, Person 647명 전부는 약 $1.7. 다시 만들지 않았다.
- **Bedrock 설명 다시 만들기 23명 (11-14, 사용자 승인, 유료):** worker에 일회성 `POST /internal/map/redescribe {artistIds(≤30), maxCalls(≤30)}`를 더했다(`MAP_ADMIN_ENDPOINT`일 때만).
  - LLM_MODE가 fake여도 이 요청만 `BedrockMapLlm`(같은 프롬프트·모델)을 쓴다. `MapService.redescribe`는 실패한 것만 한 번 더 시도하고, 호출 상한에서 멈추고, 빈 답이면 기존 설명을 그대로 둔다.
  - 결과: 호출 23번(llm_call 1666 → 1689), 토큰 입력 28,523·출력 2,648, 약 $0.04(Haiku 4.5 $1/$5 가정). OK 15명, EMPTY 8명.
  - EMPTY 8명은 근거 번호를 대지 못한 답이라 설명 가드가 버렸다. 그래서 옛 설명(생일 연도 문장)이 남아 있다: STUTS·Lil tachi·유라·조정석·ダズビー·KEIJU·kZm·Tani Yuuki. 새 설명 15명에는 그 문장이 0개다.
  - 테스트: worker `MapIntegrationTest` 1개(지정 가수만, 실패만 한 번 더, 빈 답은 유지, 상한). worker 89 통과. 18:26:54 worker 재시작. LLM_MODE는 fake 그대로다.
- **마이그레이션 (11-14, 사용자 승인):** `V33__strip_person_begin.sql`. 위 생일 정리와 같은 멱등 UPDATE 한 문장이다(Person·Character 줄의 `; 활동 시작 [0-9?-]+` 삭제, Group 유지).
  - 지시는 V34였지만 V33으로 했다. Flyway `outOfOrder`가 꺼져 있어 V34를 먼저 두면 나중에 들어올 V33(I-48)이 검증에서 막히기 때문이다. 그래서 I-48 제목 만료 메모는 V34로 고쳤다.
  - 로컬 적용: 18:35:05 api가 V33을 적용했다. 적용 전·후 대상 0행이고, 가수 조각 전체 텍스트 해시도 같다(0행 변경).
  - 테스트: worker `MapIntegrationTest` 1개(Person 정리·Character "????" 정리·Group 유지·두 번째 0행). worker 90·api 93(건너뜀 1) 통과. 18:35:00 api·worker 재시작.
- **남은 8명 설명 정리 (11-14, 사용자 승인, 무료):** STUTS·Lil tachi·유라·조정석·ダズビー·KEIJU·kZm·Tani Yuuki의 출생 연도 "활동 시작" 구절을 지웠다(8행).
  - 절 하나만 빼면 어색한 STUTS·조정석은 그 문장 전체를 지웠다. Lil tachi는 같은 날짜에서 나온 "초기 2000년대의 시대적 감성…" 문장도 지웠다.
  - 정리 뒤 23명 중 해당 문장은 0개다.
  - 벡터: 로컬 임베딩 서비스(multilingual-e5-small ONNX, `EMBED_URL` localhost:18084)라 무료다. 설명이 바뀐 23명만 오래된 상태라 `/internal/map/embed`로 23개를 다시 만들었다. llm_call은 1689로 변화 없다.

### 내 노래 발매 연도·앨범 (11-14, 사용자 승인)
- `MySongView.releaseYear`·`album`을 더했다(recent·genre·artist 모두). DB 값만 쓴다.
  - album: `track.release_title`(식별 때 고른 원곡이 처음 실린 공식 음반, l1-design 4-3)
  - releaseYear: `track.release_date` 앞 4자리. 없으면 `artist_recording.first_release_date`(그 녹음이 처음 나온 날, I-30 수집) 앞 4자리. 둘 다 없으면 null
- 리뷰 방 리뷰A(member 28) 40곡: 앨범 40/40, 연도 39/40. 빠진 1곡은 Her Blue Sky(베스트 앨범, 날짜 없음, 녹음 수집 없음)이다. artist_recording 대체로 더 채워진 곡은 0이다.
- 테스트: api 1개(원곡 음반·연도, 녹음 첫 해 대체, 둘 다 없으면 null, artist 정렬에도 같은 값). api 94(건너뜀 1)·worker 90 통과. 18:42:56 api·worker 재시작.
