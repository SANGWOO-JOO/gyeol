"""분위기로 가수 찾기 — 검색 정확도(측정 1)와 거리 기준 T(측정 3). LLM을 쓰지 않아 무료.
설계: docs/todo/vibe-search-rag.md 7절.
  python3 tools/vibe-eval/eval.py [--per-country 17] [--total 50] [--embed http://localhost:18084] [--out tools/results/vibe-eval]
필요: 로컬 postgres 컨테이너(gyeol-local-postgres-1), embed 서버(embed/app.py, 18084).

질문: 가수의 Last.fm 태그(knowledge_chunk LASTFM 청크 앞 'Last.fm 태그: ...')에서 나라 태그를 빼고
사전(TAG_KO)에 있는 태그 앞 3개를 한국어 분위기 문장으로 바꾼다. 정답은 그 가수 자신.
한계: 태그가 설명(descr) 생성 근거에도 들어 있어 점수가 실제 사용자 문장보다 높게 나올 수 있다.
"""
import argparse, json, random, re, subprocess, urllib.request
from pathlib import Path
import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument('--per-country', type=int, default=17)
ap.add_argument('--total', type=int, default=50)
ap.add_argument('--seed', type=int, default=20261118)
ap.add_argument('--embed', default='http://localhost:18084')
ap.add_argument('--out', default='tools/results/vibe-eval')
args = ap.parse_args()

# 영어 태그 → 한국어 꾸밈말/명사. 나라·지역·연대 외 의미 없는 태그는 넣지 않는다(건너뜀)
TAG_KO = {
    'pop': '팝', 'rock': '록', 'j-pop': '팝', 'jpop': '팝', 'k-pop': '아이돌 팝', 'kpop': '아이돌 팝', 'j-rock': '록', 'jrock': '록',
    'k-rock': '록', 'japanese rock': '록', 'korean rock': '록', 'hip-hop': '힙합', 'hip hop': '힙합', 'k-hiphop': '힙합', 'khh': '힙합',
    'korean hip-hop': '힙합', 'rap': '랩', 'k-rap': '랩', 'female vocalists': '여성 보컬', 'female vocalist': '여성 보컬',
    'male vocalists': '남성 보컬', 'indie': '인디', 'k-indie': '인디', 'alternative': '얼터너티브', 'rnb': '알앤비', 'r&b': '알앤비',
    'k-rnb': '알앤비', 'alternative rnb': '얼터너티브 알앤비', 'electronic': '전자음악', 'electronica': '일렉트로니카', 'indie rock': '인디 록',
    'alternative rock': '얼터너티브 록', 'indie pop': '인디 팝', 'trap': '트랩', 'singer-songwriter': '싱어송라이터', 'soul': '소울',
    'anime': '애니메이션 주제가', 'ballad': '발라드', 'folk': '포크', 'dance': '댄스', 'vocaloid': '보컬로이드', 'shoegaze': '슈게이즈',
    'punk': '펑크', 'electropop': '일렉트로팝', '80s': '80년대 감성', '70s': '70년대 감성', '90s': '90년대 감성', '60s': '60년대 감성',
    'experimental': '실험적인', 'classic rock': '클래식 록', 'metal': '메탈', 'hard rock': '하드 록', 'emo': '이모',
    'dream pop': '몽환적인 드림팝', 'jazz': '재즈', 'girl group': '걸그룹', 'boy group': '보이그룹', 'lo-fi': '로파이',
    'post-hardcore': '포스트 하드코어', 'funk': '펑크(funk)', 'soundtrack': '사운드트랙', 'ost': '사운드트랙', 'acoustic': '어쿠스틱',
    'pop rock': '팝 록', 'underground hip-hop': '언더그라운드 힙합', 'cloud rap': '몽롱한 클라우드 랩', 'synthpop': '신스팝',
    'idol': '아이돌', 'pop punk': '팝 펑크', 'psychedelic': '사이키델릭', 'gangsta rap': '갱스터 랩', 'hyperpop': '하이퍼팝',
    'grunge': '그런지', 'ambient': '잔잔한 앰비언트', 'hardcore': '하드코어', 'nu metal': '뉴메탈', 'pop rap': '팝 랩',
    'screamo': '스크리모', 'indie folk': '인디 포크', 'post-rock': '포스트록', 'oldies': '옛날 노래', 'techno': '테크노',
    'country': '컨트리', 'punk rock': '펑크 록', 'city pop': '시티팝', 'house': '하우스', 'drill': '드릴', 'video game music': '게임 음악',
    'bedroom pop': '베드룸 팝', 'emo rap': '이모 랩', 'neo-soul': '네오 소울', 'plugg': '플러그', 'art pop': '아트 팝',
    'new wave': '뉴웨이브', 'instrumental': '연주곡', 'progressive rock': '프로그레시브 록', 'rage': '레이지 랩', 'digicore': '디지코어',
    'alternative metal': '얼터너티브 메탈', 'psychedelic rock': '사이키델릭 록', 'heavy metal': '헤비메탈', 'visual kei': '비주얼계',
    'blues': '블루스', 'chiptune': '칩튠', 'shibuya-kei': '시부야계', 'garage rock': '개러지 록', 'noise pop': '노이즈 팝',
    'dubstep': '덥스텝', 'piano': '피아노', 'guitar': '기타', 'post-punk': '포스트 펑크', 'math rock': '매스 록', 'edm': 'EDM',
    'melodic hardcore': '멜로딕 하드코어', 'emocore': '이모코어', 'pluggnb': '플러그앤비', 'breakbeat': '브레이크비트',
    'drum and bass': '드럼앤베이스', 'soft rock': '소프트 록', 'folktronica': '포크트로니카', 'seiyuu': '성우 노래', 'utaite': '우타이테',
    'chill': '편안한', 'sad': '슬픈', 'mellow': '잔잔한', 'beautiful': '아름다운', 'atmospheric': '공간감 있는', 'melancholic': '쓸쓸한',
    'happy': '밝은', 'energetic': '에너지 넘치는', 'romantic': '로맨틱한', 'dark': '어두운', 'chillout': '편안한', 'downtempo': '느긋한',
    'trip-hop': '트립합', 'disco': '디스코', 'reggae': '레게', 'latin': '라틴', 'k-ballad': '발라드', 'trot': '트로트', 'enka': '엔카',
}
OFF_TOPIC = ['오늘 점심 뭐 먹지', '내일 서울 날씨 어때', '파이썬에서 리스트 정렬하는 법', '비행기 표 싸게 사는 방법',
             '삼성전자 주가 전망', '고양이가 토를 해요', '엑셀 VLOOKUP 함수 사용법', '부산까지 KTX 몇 시간 걸려',
             '감기 빨리 낫는 법', '1+1은 몇이야']
# 태그 단어 없이 사람이 쓴 듯한 정상 질문 (T 비교용 보조 분포, 정답 없음)
NATURAL = ['비 오는 밤에 혼자 듣기 좋은 잔잔한 노래', '새벽 드라이브할 때 듣는 몽환적인 음악', '신나게 운동할 때 듣는 강한 비트',
           '헤어진 뒤에 듣는 슬픈 발라드', '카페에서 흘러나올 법한 따뜻한 어쿠스틱', '여름 바닷가에 어울리는 청량한 음악',
           '공부할 때 방해 안 되는 차분한 연주', '80년대 도시의 밤 같은 레트로한 분위기', '거칠고 시끄러운 기타 소리',
           '나른한 일요일 오후 같은 노래']
COMBOS = [(col, kind) for col in ('descr', 'mixed', 'raw') for kind in ('query', 'passage')]


def q(sql):
    out = subprocess.run(['docker', 'exec', 'gyeol-local-postgres-1', 'psql', '-U', 'gyeol', '-d', 'gyeol', '-tAc', sql],
                         check=True, capture_output=True, text=True).stdout
    return [json.loads(l) for l in out.splitlines() if l.strip()]


def embed(texts, kind):
    req = urllib.request.Request(f'{args.embed}/embed', json.dumps({'texts': texts, 'kind': kind}).encode(),
                                 {'content-type': 'application/json'})
    v = np.array(json.load(urllib.request.urlopen(req, timeout=120))['vectors'], dtype=np.float32)
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def sentence(tags):
    ko = []
    for t in tags:
        k = TAG_KO.get(t.strip().lower())
        if k and k not in ko and not any(k in x or x in k for x in ko):
            ko.append(k)
        if len(ko) == 3:
            break
    return ' '.join(ko) if len(ko) >= 2 else None


# 1. 표본: 설명·descr 벡터·Last.fm 태그가 있는 가수, 나라별 고정 순서(md5) → 문장이 되는 가수만
cands = q("""SELECT row_to_json(x) FROM (
  SELECT a.id, a.name, p.seed_country AS country, substring(k.text from 'Last.fm 태그: ([^.]*)\\.') AS tags,
         row_number() OVER (PARTITION BY p.seed_country ORDER BY md5(a.id::text || '%s')) AS rn
  FROM artist a JOIN artist_profile p ON p.artist_id = a.id JOIN artist_vector v ON v.artist_id = a.id
  JOIN knowledge_chunk k ON k.entity_mbid = a.mbid AND k.entity_type = 'ARTIST' AND k.source = 'LASTFM' AND k.text LIKE 'Last.fm 태그:%%'
  WHERE p.description IS NOT NULL AND v.descr IS NOT NULL AND p.seed_country IS NOT NULL) x ORDER BY country, rn""" % args.seed)
by_c = {}
for c in cands:
    s = sentence(c['tags'].split(','))
    if s and len(by_c.setdefault(c['country'], [])) < args.per_country:
        by_c[c['country']].append({**c, 'q': s})
sample = [a for c in sorted(by_c) for a in by_c[c]][:args.total]
print(f'표본 {len(sample)}명', {c: sum(a['country'] == c for a in sample) for c in by_c})

# 2. 가수 벡터 전부 (1.5천 건이라 정확 검색으로 계산; 운영의 HNSW 근사와 순위가 조금 다를 수 있음)
rows = q("SELECT json_build_object('id', artist_id, 'raw', raw::text, 'descr', descr::text, 'mixed', mixed::text) FROM artist_vector")
ids = np.array([r['id'] for r in rows])
M = {}
for col in ('raw', 'descr', 'mixed'):
    keep = [i for i, r in enumerate(rows) if r[col]]
    m = np.array([json.loads(rows[i][col]) for i in keep], dtype=np.float32)
    M[col] = (ids[keep], m / np.linalg.norm(m, axis=1, keepdims=True))

qs = [a['q'] for a in sample]
res, out_rows = {}, {}
for col, kind in COMBOS:
    cid, m = M[col]
    qv = embed(qs, kind)
    d = 1 - qv @ m.T  # 코사인 거리 (pgvector <=> 와 같음)
    rr, hit, top1 = [], 0, []
    for i, a in enumerate(sample):
        order = np.argsort(d[i])
        rank = int(np.where(cid[order] == a['id'])[0][0]) + 1
        hit += rank <= 10
        rr.append(1 / rank if rank <= 10 else 0)
        top1.append(float(d[i][order[0]]))
        out_rows.setdefault(a['id'], {'name': a['name'], 'country': a['country'], 'q': a['q']})[f'{col}/{kind}'] = rank
    res[(col, kind)] = {'recall10': hit / len(sample), 'mrr': sum(rr) / len(sample), 'top1': top1}

print('\n| 벡터 | 접두어 | Recall@10 | MRR@10 |\n|---|---|---|---|')
for (col, kind), r in res.items():
    print(f"| {col} | {kind} | {r['recall10']:.2f} ({round(r['recall10'] * len(sample))}/{len(sample)}) | {r['mrr']:.3f} |")


# 3. 거리 기준 T: 조합마다 top-1 거리 분포 (태그 질문 / 자연 문장 / 엉뚱한 질문)
def top1(texts, col, kind):
    cid, m = M[col]
    return (1 - embed(texts, kind) @ m.T).min(axis=1)


pct = lambda a, p: float(np.percentile(a, p))
print('\n| 벡터/접두어 | 태그 질문 top-1 (p50 / p95 / 최대) | 자연 문장 (p50 / 최대) | 엉뚱한 질문 (최소 / p50) |\n|---|---|---|---|')
T = {}
for col, kind in COMBOS:
    tag = np.array(res[(col, kind)]['top1']); nat = top1(NATURAL, col, kind); off = top1(OFF_TOPIC, col, kind)
    T[f'{col}/{kind}'] = {'tag': tag.tolist(), 'natural': nat.tolist(), 'off': off.tolist(),
                          'off_detail': dict(zip(OFF_TOPIC, map(float, off))), 'natural_detail': dict(zip(NATURAL, map(float, nat)))}
    print(f'| {col}/{kind} | {pct(tag, 50):.3f} / {pct(tag, 95):.3f} / {tag.max():.3f} | {pct(nat, 50):.3f} / {nat.max():.3f} '
          f'| {off.min():.3f} / {pct(off, 50):.3f} |')

out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
(out / 'result.json').write_text(json.dumps({'summary': {f'{c}/{k}': {x: v for x, v in r.items() if x != 'top1'} for (c, k), r in res.items()},
                                             'per_artist': list(out_rows.values()), 'distance': T}, ensure_ascii=False, indent=1))
print(f'\n저장: {out}/result.json')
