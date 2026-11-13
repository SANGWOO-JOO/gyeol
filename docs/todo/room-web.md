# 음악 방 웹 화면 범위 (갈래 A, 초안 2026-11-13)

상태: **확정(11-13).** 갈래 A(새 세션, `web/`만 고침)가 이 문서대로 만든다.
근거: 프로토타입 캔버스 [Design](https://claude.ai/artifact/YADs8B6nGSj92PNn62cc5U) (보드 제목의 번호), `room-api-db.md` 3절(API↔화면 번호), `docs/design/ci/README.md`. 병렬 규칙은 `parallel.md`.

## 0. 결정 (11-13, 사용자 확정)

| # | 질문 | 결정 |
|---|---|---|
| Q1 | 지도("음악 은하") 위에서 일어나는 화면을 A가 어디까지 만드나 | **(11-13 변경) Codex에 맡기던 지도 화면도 Claude(갈래 A)가 만든다.** 순서: 방 틀(머리·아래 탭 4개·하단 판·알림)과 지도 자리(임시 가수 목록)를 먼저 → 다른 화면 → 마지막에 지도 그림(행성·궤도·위성, 확대·이동)과 연출(4-e·4-f·4-h, 금빛 겹침)로 임시 판을 교체. 지도 자리는 처음부터 교체 가능한 컴포넌트 하나로 분리한다. 일러스트가 필요한 부분은 SVG·CSS로 그리고, 이미지 생성이 필요하면 관리 세션에 보고 |
| Q2 | PC 화면도 이번에 만드나 | **휴대폰 먼저.** 같은 컴포넌트로 PC에서도 깨지지 않게(입력 화면은 가운데 카드, 판은 오른쪽). PC 전용 배치(지도 왼쪽 + 오른쪽 판 탭)는 지도가 붙은 뒤 2차 |
| Q3 | 디자인 기준 | **캔버스 화면 구성 + CI 색·글꼴을 따른다.** frontend-design 스킬은 간격·글자 위계·접근성 다듬기에만 쓰고 새 방향을 만들지 않는다 |
| Q4 | `docs/design/ci/`·`hero/`를 커밋하나 | **A 시작 전에 커밋한다.** `map-v1-300.png`(실제 가수 이름)는 계속 제외 |

상황 변경(11-13): ④ 선물 API가 main에 들어가(`033f48b`) 선물 화면(4-j·4-k·4-l)은 A1로 앞당긴다. ⑤ 놀이터는 구현 중이라 8-a·8-b는 ⑤ 합친 뒤.

## 1. 범위표

구분: **A1** 지금 만듦 · **A2** ④⑤ API가 나온 뒤 · **C** 지도(11-13부터 Codex 대신 갈래 A가 마지막 단계에 만듦) · **⑥** 관리자 단계 뒤 · **2차** 이번에 안 함.
(11-13 갱신: ①~⑥ API가 모두 main에 있어 A2·⑥ 표시 화면도 지금 만든다.)
보드 이름은 캔버스 파일명(`.dc.html` 생략). PC판은 이름 끝 `-pc`.

### 1-1. 시작·방 만들기·인증

| 번호 | 화면 | 보드 (휴대폰 / PC) | API | 구분 | 비고 |
|---|---|---|---|---|---|
| 1 | 첫 화면 | Main / Home-desktop | — | A1 | 기존 `Home.tsx`(청취 기록·곡 고르기 입구)와 어떻게 합칠지 A가 설계안에서 제안 |
| 2 | 방 만들기 (닉네임·암호·이메일) | Create-room / -pc | `POST /room-drafts` | A1 | |
| 3 | 이메일 인증 코드 | Verify-email / -pc | `POST /room-drafts/{id}/verify`·`/resend` | A1 | 로컬은 코드가 api 로그에 찍힘(`LogMailSender`) |
| 5 | 초대받은 화면 (참가하기 / 나는 OO예요) | Invite / -pc | `GET /rooms/{rid}`, `POST /rooms/{rid}/members` | A1 | 열쇠 없이 부를 수 있는 API는 공개 요약뿐 |
| 6-a | 나는 OO예요 · 4자리로 다시 들어오기 | Acct-rejoin / -pc | `POST /rooms/{rid}/session` | A1 | 5회 실패 잠금(423) 안내 |
| 6-b | 참가자 암호를 잊었을 때 | Acct-code / -pc | — (방장에게 요청 안내) | A1 | 화면 안내만 |
| 6-d | 방장 암호 재설정 (이메일) | Acct-host-reset / -pc | `POST /rooms/{rid}/host-reset`·`/verify` | A1 | 실제 메일은 B 이후, 로컬은 로그 |
| 6-g | 들어갈 수 없는 방 (꽉 참) | Acct-full / -pc | 참가 API 오류 | A1 | |

### 1-2. 방 안 (틀·담기·내 노래·참가자)

| 번호 | 화면 | 보드 (휴대폰 / PC) | API | 구분 | 비고 |
|---|---|---|---|---|---|
| 4 | 방 (음악 은하) | Room-map / Room-desktop | `GET /rooms/{rid}/map` | **틀 A1 · 지도 C** | Q1 |
| 4′ | 행성(가수) 눌렀을 때 | Planet-sheet | `GET /rooms/{rid}/artists/{id}` | **시트 A1 · 지도 연결 C** | 하단 시트 내용(설명·근거·방 안 노래·가까운 가수)은 A |
| 4-a | 내 노래 (최근 순) | Room-mine / Room-desktop-mine | `GET /rooms/{rid}/me/songs?sort=recent` | A1 | |
| 4-a2 | 내 노래 장르별 | Room-mine-genre / -pc | `sort=genre` | 2차 | API가 최근 순만 구현됨 (`room-api-db.md` 7절) |
| 4-a3 | 내 노래 아티스트별 | Room-mine-artist / -pc | `sort=artist` | 2차 | 같은 이유. 웹에서 묶어 보여 줄지 A가 제안 가능 |
| 4-b | 참가자 판 (방장) | Room-people / Room-desktop-people | `GET /rooms/{rid}/members` | A1 | |
| 4-c | 빈 방 (0곡) | Room-empty / -pc | 지도 응답 0곡 | **A1 (빈 상태 안내)** · 배경 C | |
| 4-d | 노래 검색 결과 | Room-search / -pc | `GET /rooms/{rid}/search` | A1 | 같은 가수·같은 노래·이미 담음·새 가수 표시 |
| 4-e | 담은 노래가 뜨는 순간 | Room-born / -pc | `POST /rooms/{rid}/songs` 201 | **알림 A1 · 연출 C** | 담기 성공 알림·되돌리기는 A |
| 4-f | 첫 노래 (1곡) | Room-first / -pc | — | C | 지도 연출 |
| 4-g | 가수 준비 중 | Room-preparing / -pc | 202 + `GET …/song-requests/{id}`, 지도 `preparing` | **진행 표시 A1** · 지도 위 표시 C | |
| 4-h | 같은 노래가 겹치는 순간 | Room-match / -pc | 201 응답의 겹침 여부 | **알림 A1 · 연출 C** | |
| 4-i | 노래를 찾지 못함 | Room-notfound / -pc | `song_request` FAILED | A1 | |

### 1-3. 내 정보·방장 메뉴

| 번호 | 화면 | 보드 (휴대폰 / PC) | API | 구분 | 비고 |
|---|---|---|---|---|---|
| 6-h | 내 정보 (닉네임·이 기기에서 나가기) | Acct-me / -pc | `GET·PATCH /rooms/{rid}/me`, `DELETE /rooms/{rid}/session` | A1 | 선물 상태 표시는 A2 |
| 6-c | 방장: 참가자 관리 (새 4자리) | Acct-host-member / -pc | `POST …/members/{mid}/pin`, `DELETE …/members/{mid}` | A1 | 새 암호는 한 번만 보여 줌 |
| 6-e | 방 설정 (방장) | Acct-settings / -pc | `PATCH /rooms/{rid}` | A1 | |
| 6-f | 방 삭제 확인 | Acct-delete / -pc | `DELETE /rooms/{rid}` | A1 | 7일 뒤 완전 삭제 안내 |

### 1-4. ④⑤ 뒤 · 관리자 · 기타

| 번호 | 화면 | 보드 | API | 구분 |
|---|---|---|---|---|
| 4-j | 50곡 선물 상자 · 캐릭터 만드는 중 | Gift-open / -pc | `GET …/me/gifts`, `POST …/gifts/{id}/open` | A1 (④ API 있음, `room-api-db.md` 9절) |
| 4-k | 새 캐릭터 받기 · 바꿀까요 | Gift-pick / -pc | `GET …/gifts/{id}`, `PUT …/me/avatar` | A1 (④) |
| 4-l | 선물 표시 (탭 배지·내 노래 줄) | Gift-states / -pc | `GET …/me` | A1 (④) |
| 8-a | 놀이터 · 코드 맞추기 고르기 | Play-home / -pc | `POST …/code-match` | A2 (⑤) |
| 8-b | 코드 맞추기 결과 | Play-code / -pc | `GET …/code-match` | A2 (⑤) · **표시 규칙(11-13 결정 "라"):** 둘 중 적은 쪽 곡 수가 5~9곡(신뢰도 LOW)이면 % 점수를 보여 주지 않고 겹친 것(같은 가수 수·같은 구역 수·같은 노래)과 이유 문장만, "각자 10곡을 넘기면 코드 점수가 나와요" 안내. 10곡부터 % 점수. 근거: 5곡은 실제보다 평균 약 35점, 10곡은 약 20점 낮게 나옴(`room-api-db.md` 11절 측정) |
| 7-a~7-c | 관리자 로그인·목록·상세 (PC) | Admin-login·list·detail | `/admin/…` | ⑥ |
| CI | 로고·색 | CI | — | 참고 자료 |
| — | 캔버스의 Artboard 1 | Artboard-acdw | — | 제외 (빈 판으로 보임) |

**개수 (휴대폰 기준):** A1 25(그중 틀만 만드는 것 6, 선물 3 포함) · A2 2(놀이터) · C 1 · 2차 2 · ⑥ 3.

## 2. 라우트 (제안)

| 주소 | 화면 |
|---|---|
| `/` | 1 첫 화면 |
| `/rooms/new` | 2 방 만들기 → 3 인증 (같은 페이지 단계) |
| `/rooms/:rid` | 열쇠 없음: 5 초대받은 화면 · 6-a 다시 들어오기 / 열쇠 있음: 4 방 (탭: 은하·내 노래·참가자) |
| `/rooms/:rid/me` | 6-h 내 정보 |
| `/rooms/:rid/settings` | 6-e·6-f 방장 설정 |
| `/rooms/:rid/host-reset` | 6-d 방장 암호 재설정 |

기존 `/upload`, `/pick`, `/r/:token`은 그대로 둔다. 하단 시트(4′·4-d·6-c)는 주소 없이 방 화면 안에서 연다.

## 3. 공통 규칙

- **열쇠:** 참가·인증 응답의 열쇠를 방마다 `localStorage`에 두고, API 호출에 `X-Room-Key` 헤더로 보낸다(쿠키도 서버가 설정). 401이면 6-a로 보낸다.
- **오류 문구:** 423(잠김)·429(요청 제한)·하루 100곡 제한은 화면별 안내 문구로. 서버 문구를 그대로 띄우지 않는다.
- **개인정보:** 방장 이메일은 화면에 보이지 않는다(재설정 화면 포함).
- **디자인:** `docs/design/ci/README.md` 색(밤 보라 `#141225` 바탕, 은빛·로즈·라벤더·피치)과 Quicksand 워드마크. 캔버스 화면 구성을 따르고, 기존 `styles/ui.module.css`와 어떻게 나눌지는 A가 설계안에서 정한다.
- **확인:** 화면마다 휴대폰(390px)·PC(1440px)에서 열어 본다. 개발 서버는 원래 폴더의 것을 쓴다(`parallel.md` 3절).

## 4. A의 진행 순서 (제안)

1. 설계안: 라우트·컴포넌트·스타일 구조, Q1~Q4 결정 반영 → 사용자 승인
2. 방 만들기·인증·초대·다시 들어오기 (2·3·5·6-a·6-b·6-g)
3. 방 틀과 담기 (4 틀·4-d·4-e 알림·4-g·4-h 알림·4-i·4-c 안내)
4. 내 노래·참가자·하단 시트 (4-a·4-b·4′ 시트)
5. 내 정보·방장 메뉴 (6-h·6-c·6-e·6-f·6-d)
6. 선물 (4-j~4-l, ④ API 있음)
7. 놀이터 (8-a·8-b, ⑤ API 있음)
8. 관리자 (7-a~7-c, PC, ⑥ API 있음)
9. 지도 그림과 연출 (4·4′ 지도 연결·4-c 배경·4-e·4-f·4-g 지도 위 표시·4-h, 11-13 Codex → Claude)
