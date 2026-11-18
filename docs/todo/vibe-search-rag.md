# 분위기로 가수 찾기: Naive RAG (설계, 11-18)

상태: **설계만. 코드는 승인 뒤.** 측정은 Bedrock 비용이 들어 실행 전 확인.

## 1. 목적

음악 방에서 "비 오는 밤에 듣기 좋은 몽환적인 밴드"처럼 **자유 문장**으로 가수를 찾고, 찾은 가수의 근거로 추천 이유를 받는다.

- 지금 RAG 두 흐름은 교과서형 Naive RAG가 아니다.
  - 가수 설명(14쪽): 대상이 정해져 ID 일치 조회
  - 코드 맞추기 이유(16쪽): 질의가 문장이 아니라 두 사람의 가수 벡터
- 이 기능은 **질문 임베딩 → 벡터 top-k → 근거 삽입 → 생성 → 출력 검증**을 그대로 따른다 (Gao et al., 2023, Naive RAG).
- 지금 서비스 검색에 쓰이지 않는 `idx_artist_vector_hnsw`를 처음으로 실제 질의에 쓴다.
- 방 안 기능: 결과 가수를 눌러 가수 화면 → 노래 담기로 이어진다. 방 멤버가 이미 담은 가수에는 표시를 단다.

## 2. 지금 있는 것 (코드 기준)

| 부품 | 위치 | 비고 |
|---|---|---|
| 임베딩 서버 | `embed/app.py` (FastAPI, multilingual-e5-small 384차원) | `kind`로 `query`/`passage` 접두어 |
| 임베딩 클라이언트 | `worker/.../describe/EmbedClient.embed(texts, kind)` | worker에만 있음 |
| 가수 벡터 | `artist_vector.raw`·`descr`·`mixed` (`V23__artist_map.sql`) | `MapService.kt:217–219`에서 **passage**로 만듦 |
| HNSW | `idx_artist_vector_hnsw` (`mixed`, cosine) | `raw`·`descr`에는 인덱스 없음 |
| 근거 | `knowledge_chunk`, `artist_profile.evidence` | 가수 설명과 같은 청크 |
| LLM·검증 틀 | `BedrockCodeMatchLlm`(도구 스키마, temp 0, 번호 검증) | 같은 방식 재사용 |
| 비동기 패턴 | 코드 맞추기: api가 요청 저장 → 큐 → worker 생성 → api 조회(status) | 같은 패턴 재사용 |

## 3. 흐름

```
[인덱싱] 이미 있음: 가수 설명·근거 → e5 passage 임베딩 → artist_vector (HNSW)

[질의]  api  POST /api/rooms/{rid}/vibe-search {q}
          ├─ 입력 검사 (길이 2~100자, 하루 횟수 제한)
          ├─ 같은 질문(정규화 해시) 결과가 있으면 재사용
          └─ 요청 저장(status=PENDING) → 큐
        worker
          ├─ 1 검색: q를 e5 "query:"로 임베딩 → pgvector top-10 (설명·근거 있는 가수만)
          ├─ 2 생성: 10명의 설명 + [청크 ID] 근거를 프롬프트에 넣고 Haiku가 3~5명 고르고 이유 작성
          ├─ + 출력 검증: 고른 가수가 top-10 안인지, 청크 ID가 준 것인지, 거절문 여부
          └─ 저장(status=DONE / 검증 실패 시 이유 없이 검색 결과만)
        api  GET /api/rooms/{rid}/vibe-search/{id} → 가수 카드 + 이유 + 근거 출처
```

검색 쿼리 (안):

```sql
SELECT v.artist_id, v.descr <=> :q AS d
FROM artist_vector v JOIN artist_profile p ON p.artist_id = v.artist_id
WHERE p.description IS NOT NULL AND cardinality(p.evidence) > 0
ORDER BY v.descr <=> :q
LIMIT 10;
```

## 4. 정할 것

| # | 항목 | 후보 | 제안 |
|---|---|---|---|
| D1 | 비교할 벡터 | `descr`(설명만) / `mixed` / `raw` | **측정으로 정함.** 질문이 분위기 문장이라 한국어 설명인 `descr`이 유리할 것으로 예상(추측). `descr`로 정하면 HNSW 인덱스를 `descr`에 새로 만든다(V28) |
| D2 | 질문 접두어 | `query:` / `passage:` | `query:` (가수 벡터가 passage라 e5 권장 조합) |
| D3 | top-k / 보여 줄 수 | 10→3~5 / 5→5 | 검색 10, LLM이 3~5명 고름 |
| D4 | 실행 위치 | api 동기 / worker 비동기 | **worker 비동기** (EmbedClient가 worker에 있고, 코드 맞추기와 같은 패턴. 응답 2~4초 예상, 추정) |
| D5 | 검색 범위 | 지도 전체 1,458명 / 방 멤버 가수만 | **지도 전체**. 방 멤버가 담은 가수는 카드에 표시 |
| D6 | 재사용 | 질문 해시 같으면 재사용 / 매번 생성 | 재사용 (지도 판 바뀌면 무효) |
| D7 | 비용 제한 | 멤버당 하루 N회 | N=20 (Haiku 1회 약 $0.003, 추정) |
| D8 | 근거 없는 질문 | 가까운 가수가 없을 때 | 거리 기준 T보다 멀면 "맞는 가수를 못 찾았어요". T는 측정으로 정함 |

## 4-1. 확정 (11-18)

D1~D8 제안대로. D1(벡터 열)·D2(접두어)·D8(T)은 측정 뒤 값만 바꿀 수 있게 설정으로 둔다:
`gyeol.worker.vibe-search.vector-column`(기본 `descr`), `query-prefix`(기본 `query`), `max-distance`(기본 1.0 = 거르지 않음).

## 4-2. API 계약 (웹·백엔드 병렬 작업 기준)

```
POST /api/rooms/{rid}/vibe-search          body {"q": "비 오는 밤 몽환적인 밴드"}
  202 {"id": 12, "status": "PENDING"}       새 요청
  200 VibeSearchView                        같은 질문 재사용
  400 {"error": "QUERY_LENGTH"}             2~100자 밖
  429 {"error": "DAILY_LIMIT"}              멤버당 하루 20회

GET  /api/rooms/{rid}/vibe-search/{id}  → VibeSearchView

VibeSearchView {
  id, query, status: "PENDING" | "DONE" | "FAILED", createdAt,
  picks: [ { artistId, name, imageUrl, reason | null, inRoomBy: [nickname] } ],   // DONE일 때 3~5개
  evidence: [ { id, source, url } ],
  notFound: boolean                         // D8: 가까운 가수 없음
}
```

웹은 POST 뒤 `status`가 DONE/FAILED가 될 때까지 GET을 1초 간격으로 부른다 (코드 맞추기와 같은 방식). 이유가 검증에서 빠지면 `reason`은 null이고 카드만 보인다.

## 5. DB (안)

`V28__vibe_search.sql`

```sql
CREATE TABLE vibe_search (
  id BIGSERIAL PRIMARY KEY,
  room_id BIGINT NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  member_id BIGINT NOT NULL,
  query TEXT NOT NULL,
  query_hash VARCHAR(64) NOT NULL,
  map_version BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,          -- PENDING / DONE / FAILED
  hits JSONB,                           -- 검색 top-10 [{artistId, d}]
  picks JSONB,                          -- LLM이 고른 가수와 이유
  evidence BIGINT[] NOT NULL DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- D1이 descr이면
CREATE INDEX idx_artist_vector_descr_hnsw ON artist_vector USING hnsw (descr vector_cosine_ops);
```

## 6. 출력 검증

- 고른 가수 ID가 검색 top-10 안에 있어야 함. 아니면 그 가수 제거
- 청크 ID는 프롬프트에 준 것만 인정. 유효 ID 0개면 이유 버림 (가수 카드는 보여 줌)
- 거절문·빈 이유 정규식 검사 (가수 설명과 같은 규칙)
- 시스템 프롬프트: 근거에 없는 내용 금지, 가수 이름·나라 언급 규칙은 가수 설명과 같게

## 7. 측정 (Bedrock 약 $1~2, 추정, 실행 전 확인)

도구: `tools/vibe-eval/eval.py` (새로 작성). 결과는 `tools/results/` (gitignore).

1. **검색 정확도 (LLM 없음, 무료)**
   - 질문 만들기: 가수마다 Last.fm 태그 3개를 한국어 문장으로 바꾼 질문(예: "dream pop, shoegaze, female vocal" → "몽환적인 드림팝 여성 보컬"). 50명, 고정 시드
   - 정답: 그 가수가 top-10 안에 있는가 (Recall@10), 순위(MRR)
   - 비교: `descr` / `mixed` / `raw` × 접두어 `query`/`passage` → D1·D2 결정
   - 주의: 태그가 설명 생성 근거에도 들어 있어 점수가 높게 나올 수 있음. 포트폴리오에 쓸 때 이 조건을 함께 적는다
2. **생성 품질 (Bedrock)**
   - 사람이 쓴 분위기 질문 20개(태그 단어 없이)로 실행
   - Sonnet 심판: 이유 문장의 근거 뒷받침 비율, 고른 가수가 질문에 맞는지(맞음/애매/안 맞음)
   - 비교: 검색 결과를 넣음(RAG) vs 질문만 주고 가수 추천(근거 없음) — 근거 없음에서는 지도에 없는 가수·지어낸 설명 비율도 셈
3. **거리 기준 T (D8)**: 엉뚱한 질문 10개("오늘 점심 뭐 먹지")의 top-1 거리 분포와 정상 질문 분포를 비교

## 8. 테스트

- 검색 쿼리: 설명·근거 없는 가수 제외, 거리순, 지도 판 필터
- 검증: top-10 밖 가수 제거, 준 적 없는 청크 ID 제거, 유효 ID 0개면 이유 없음
- 재사용: 같은 질문 해시면 Bedrock 미호출
- 횟수 제한, 입력 길이
- 가짜 모드(LLM 없음)에서 검색 결과만 반환

## 9. 웹 (안)

- 방 화면 상단 검색 옆 "분위기로 찾기" 탭 → 문장 입력 → 가수 카드 3~5개(사진, 이유 한두 문장, 출처 링크, "방에서 OO가 담음" 표시)
- 카드 누르면 기존 가수 화면(`artist-search.md` ②)으로

## 10. 하지 않는 것

- 곡 단위 검색 (가수 단위만)
- 질문 재작성·리랭킹 (Advanced RAG 단계는 측정 뒤 필요할 때)
- 지도에 없는 가수 추천 (생성이 가수를 지어내지 않게 top-10 밖은 버림)

## 측정 결과 (11-18, 측정 1·3, 무료)

도구 `tools/vibe-eval/eval.py`, 결과 `tools/results/vibe-eval/result.json`(커밋 제외). 로컬 DB의 가수 벡터 1,480개(descr 1,425개) 전체를 정확 검색(코사인 거리)으로 계산했다. 운영은 HNSW 근사라 순위가 조금 다를 수 있다.

**질문**: 설명·descr 벡터·Last.fm 태그가 있는 가수 50명(JP 17, KR 17, US 16, md5 고정 순서). 태그에서 나라 태그를 빼고 사전으로 앞 3개를 한국어로 바꿨다(LLM 없음). 예: "그런지 여성 보컬 얼터너티브 록", "발라드 남성 보컬 아이돌 팝". 정답은 그 가수 하나.

### 1. 검색 정확도 → D1·D2

| 벡터 | 접두어 | Recall@10 | MRR@10 |
|---|---|---|---|
| **descr** | **query** | **0.48 (24/50)** | **0.224** |
| descr | passage | 0.36 (18/50) | 0.162 |
| mixed | query | 0.24 (12/50) | 0.150 |
| mixed | passage | 0.20 (10/50) | 0.097 |
| raw | query | 0.06 (3/50) | 0.025 |
| raw | passage | 0.00 (0/50) | 0.000 |

- **D1 = `descr`, D2 = `query`.** 두 축 모두 차이가 크다(descr이 mixed의 2배, raw는 거의 못 찾음: 영어 원문 위주라 한국어 분위기 문장과 멀다). V28에 descr HNSW 인덱스를 만든다.
- 한계 ① 태그가 설명 생성 근거에도 들어 있어 실제 사용자 문장보다 점수가 높게 나올 수 있다. ② 반대로 "팝 록"처럼 흔한 태그 질문은 맞는 가수가 수십 명이라 정답 1명 기준 Recall은 낮게 나온다(예: "록 팝 남성 보컬"의 정답 순위 252). 그래서 절대값보다 조합 간 비교로 읽는다. ③ 표본 50명.

### 3. 거리 기준 T → D8 (descr/query 기준, 코사인 거리)

| 질문 | top-1 거리 |
|---|---|
| 태그 질문 50개 | p50 0.106 · p95 0.138 · 최대 0.163 |
| 사람이 쓴 듯한 분위기 문장 10개(태그 단어 없음) | p50 0.156 · 최대 0.176 ("나른한 일요일 오후 같은 노래") |
| 엉뚱한 질문 10개 | 최소 0.186 ("삼성전자 주가 전망") · p50 0.201 · 최대 0.226 |

- **T = 0.18** (거리 > 0.18이면 notFound). 이 표본에서는 둘을 모두 가른다. 다만 정상 최대 0.176과 엉뚱한 최소 0.186 사이 여유가 0.01뿐이다(e5 거리가 좁은 범위에 몰림). 설정값으로 두고 측정 2의 사람 질문 20개로 다시 확인한다. 애매한 문장이 막히는 쪽(정상 문장 notFound)이 더 나쁘다면 0.185까지 올릴 수 있다.
- 다른 조합 참고: mixed/passage·raw/passage는 엉뚱한 질문이 정상보다 가까워(최소 0.117) 거리 기준으로 가를 수 없다.

## 측정 2 결과: 생성 품질 (11-18, Bedrock 약 $1.23)

도구 `tools/vibe-eval/gen_eval.py`, 결과 `tools/results/vibe-gen.json`(커밋 제외). 사람이 쓴 분위기 질문 20개(태그 단어 없음), 운영과 같은 검색(descr·query·top-10)과 프롬프트, 심판 Sonnet 4.6(그 가수의 저장된 설명만 근거로 판정), 1회.

| 지표 | RAG 켬 | RAG 끔 (질문만) |
|---|---|---|
| 근거로 뒷받침되는 문장 | **97/115 (84%)** | 39/144 (27%) |
| 같은 조건 (DB에 설명 있는 가수만) | 84% | 39/77 (51%) |
| 근거와 어긋난 문장 | 2 | 1 |
| 질문 적합 GOOD / OK / BAD | 47 / 28 / 4 | 20 / 73 / 1 |
| 지도에 있는 가수 | 100% (검색 결과 안에서만) | 46/94 (49%) |

- 켬: 고른 79명 모두 출력 검증 통과, 고른 가수의 검색 순위 평균 5.5
- 끔의 적합도는 지도에 없는 가수(근거 없음)를 질문·이유만 보고 판정한 것이 섞여 참고만
- **거리 기준 문제**: T=0.18에서 정상 질문 2개가 notFound("헤어지고 나서 혼자 울고 싶을 때" 0.187, "혼자 여행 떠나는 기차 안에서" 0.196). 측정 3의 엉뚱한 질문(0.186~0.226)과 범위가 겹쳐 거리 하나로 가를 수 없다
- **결정 (B안)**: `max-distance` 기본 0.22로 느슨하게, 시스템 프롬프트에 "관계없는 질문이면 picks 빈 배열" 추가, 실제 LLM이 아무도 안 고르면 카드로 채우지 않고 notFound (가짜 LLM은 그대로 검색 결과 카드). 확인(11-18, Haiku 12회 약 $0.04): 엉뚱한 질문 10개 모두 notFound(거리 2개 + LLM 빈 응답 8개), 막혔던 정상 질문 2개는 4~5명 추천 (12/12)

## 11. 포트폴리오 반영 (측정 뒤)

- 새 쪽 또는 16쪽 옆: "분위기로 가수 찾기 · Naive RAG" — 인덱싱(재사용) → 검색(HNSW top-10) → 생성 → 출력 검증, Recall@10과 근거 뒷받침 비율
- 14·16·이 기능으로 "대상이 정해짐 = ID 조회 / 대상이 열림 = 벡터 검색" 구분을 보여 줌

## 12. 공수 (추정)

| 작업 | 일 |
|---|---|
| V28·검색 쿼리·저장소 | 0.5 |
| api 요청·조회, worker 처리·프롬프트·검증 | 1 |
| 검색 정확도 측정(무료) → D1·D2 확정 | 0.5 |
| 웹 탭·카드 | 0.5~1 |
| 생성 품질 측정 | 0.5 |
