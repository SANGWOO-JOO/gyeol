"""분위기로 가수 찾기 측정 2: 생성 품질 (docs/todo/vibe-search-rag.md 7절). Bedrock 비용이 든다 (질문 20개 약 $0.5~1.5, 추정)
- RAG 켬: 운영과 같은 검색(descr, query 접두어, top-10) + 같은 프롬프트로 Haiku가 고르고 이유 → 같은 출력 검증
- RAG 끔: 후보 없이 질문만 주고 Haiku가 가수 3~5명 추천
- 심판 Sonnet 4.6: 이유 문장마다 그 가수의 저장된 설명 기준 SUPPORTED/UNSUPPORTED/CONTRADICTED, 질문 적합도 GOOD/OK/BAD
  끔에서 지도에 없는 가수는 근거 자료가 없어 문장을 모두 UNSUPPORTED로 센다 (적합도만 심판)
  python3 tools/vibe-eval/gen_eval.py [--out tools/results/vibe-gen]
필요: 로컬 postgres 컨테이너, embed 서버(18084), AWS CLI(Bedrock 권한).
"""
import argparse, json, os, re, subprocess, sys, tempfile, time, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument('--out', default='tools/results/vibe-gen')
ap.add_argument('--embed', default='http://localhost:18084')
ap.add_argument('--region', default='ap-northeast-2')
ap.add_argument('--max-distance', type=float, default=0.22)
args = ap.parse_args()

HAIKU = 'global.anthropic.claude-haiku-4-5-20251001-v1:0'
SONNET = 'global.anthropic.claude-sonnet-4-6'
PRICE = {HAIKU: (1, 5), SONNET: (3, 15)}  # 100만 토큰당 USD (공개 단가, 추정)
MAX_PICKS = 5

# 사람이 쓴 분위기 질문 (태그 단어를 피하고 상황·느낌으로)
QUERIES = [
    '비 오는 밤에 창밖 보면서 듣기 좋은 노래',
    '새벽 드라이브할 때 틀어 놓을 몽환적인 음악',
    '운동할 때 심장 뛰게 하는 신나는 노래',
    '나른한 일요일 오후 같은 노래',
    '헤어지고 나서 혼자 울고 싶을 때',
    '카페에서 공부할 때 방해 안 되는 잔잔한 음악',
    '여름 바닷가에서 친구들이랑 듣고 싶은 노래',
    '화가 났을 때 소리 지르고 싶은 강한 음악',
    '첫사랑이 떠오르는 풋풋한 노래',
    '오래된 필름 영화 같은 아날로그 감성',
    '잠 안 오는 밤에 조용히 듣는 목소리',
    '무대에서 다 같이 뛰는 페스티벌 분위기',
    '겨울 아침에 따뜻한 차 마시며 듣는 노래',
    '도시의 밤거리를 걷는 세련된 느낌',
    '기타 소리가 가득한 거친 밴드 음악',
    '춤추고 싶어지는 그루브 있는 노래',
    '슬픈데 위로가 되는 노래',
    '90년대 감성이 나는 노래',
    '혼자 여행 떠나는 기차 안에서',
    '목소리가 허스키하고 깊은 가수',
]

ON_SYSTEM = f"""너는 음악 방의 "분위기로 가수 찾기"를 돕는다. 사용자의 질문에 가장 잘 맞는 가수를 후보 안에서만 3~{MAX_PICKS}명 골라, 잘 맞는 순서로 적는다.
가수마다 질문과 닿는 이유를 한국어 한두 문장(해요체)으로 쓴다. 후보 설명에 있는 장르·분위기·소리만 쓰고, 설명에 없는 내용은 추측하지 않는다.
나라, 국적, 도시, 언어, 출신지는 언급하지 않는다. 다른 가수 이름은 쓰지 않는다.
artistId에는 후보의 가수 번호를, evidence에는 그 이유의 근거가 된 설명 줄 끝의 [번호]를 적는다.
질문에 맞는 근거가 없는 가수는 고르지 않는다. 사과하거나 쓸 수 없는 이유를 적지 않는다.
질문이 음악 분위기와 관계없거나 후보 중 맞는 가수가 없으면 picks를 빈 배열로 둔다."""  # VibeSearchService.SYSTEM과 같게 (11-18 측정 2는 이 마지막 줄 추가 전)
ON_SCHEMA = {'type': 'object', 'required': ['picks'], 'properties': {'picks': {'type': 'array', 'items': {
    'type': 'object', 'required': ['artistId', 'reason', 'evidence'],
    'properties': {'artistId': {'type': 'integer'}, 'reason': {'type': 'string'}, 'evidence': {'type': 'array', 'items': {'type': 'integer'}}}}}}}

OFF_SYSTEM = f"""너는 음악 추천을 돕는다. 사용자의 질문에 잘 맞는 실제 가수를 3~{MAX_PICKS}명 골라, 잘 맞는 순서로 적는다.
가수마다 질문과 닿는 이유를 한국어 한두 문장(해요체)으로 쓴다. 장르·분위기·소리 중심으로 쓴다.
나라, 국적, 도시, 언어, 출신지는 언급하지 않는다. 다른 가수 이름은 쓰지 않는다."""
OFF_SCHEMA = {'type': 'object', 'required': ['picks'], 'properties': {'picks': {'type': 'array', 'items': {
    'type': 'object', 'required': ['name', 'reason'],
    'properties': {'name': {'type': 'string'}, 'reason': {'type': 'string'}}}}}}

JUDGE_SYSTEM = """너는 음악 추천 심판이다. 사용자 질문, 추천된 가수의 근거 자료(그 가수의 음악 설명), 추천 이유가 주어진다.
1) 추천 이유를 문장 단위로 나누고, 문장마다 근거 자료로 뒷받침되는지 판정한다.
SUPPORTED: 문장의 핵심 주장(장르·분위기·악기·보컬·시대 등)이 근거 자료에 있거나 바로 도출된다.
UNSUPPORTED: 근거 자료에 그 내용이 없다. CONTRADICTED: 근거 자료와 어긋난다.
2) 근거 자료에 비추어 이 가수가 질문에 맞는지 fit을 판정한다: GOOD(잘 맞음) / OK(어느 정도) / BAD(안 맞음).
너의 배경지식으로 판단하지 말고 오직 근거 자료만 기준으로 판정한다. 근거 자료가 '(없음)'이면 문장은 모두 UNSUPPORTED, fit은 질문과 이유만 보고 판정한다."""
JUDGE_SCHEMA = {'type': 'object', 'required': ['sentences', 'fit'], 'properties': {
    'sentences': {'type': 'array', 'items': {'type': 'object', 'required': ['text', 'verdict'], 'properties': {
        'text': {'type': 'string'}, 'verdict': {'type': 'string', 'enum': ['SUPPORTED', 'UNSUPPORTED', 'CONTRADICTED']}}}},
    'fit': {'type': 'string', 'enum': ['GOOD', 'OK', 'BAD']}}}


def q(sql):
    out = subprocess.run(['docker', 'exec', 'gyeol-local-postgres-1', 'psql', '-U', 'gyeol', '-d', 'gyeol', '-tAc', sql],
                         check=True, capture_output=True, text=True).stdout
    return [json.loads(l) for l in out.splitlines() if l.strip()]


def embed(text):
    req = urllib.request.Request(f'{args.embed}/embed', json.dumps({'texts': [text], 'kind': 'query'}).encode(), {'content-type': 'application/json'})
    return json.load(urllib.request.urlopen(req, timeout=120))['vectors'][0]


def converse(model, system, user, tool, schema, max_tokens):
    req = {'modelId': model, 'system': [{'text': system}], 'messages': [{'role': 'user', 'content': [{'text': user}]}],
           'toolConfig': {'tools': [{'toolSpec': {'name': tool, 'inputSchema': {'json': schema}}}], 'toolChoice': {'tool': {'name': tool}}},
           'inferenceConfig': {'maxTokens': max_tokens, 'temperature': 0}}
    with tempfile.NamedTemporaryFile('w', suffix='.json', delete=False) as f:
        json.dump(req, f, ensure_ascii=False)
    for attempt in range(5):
        r = subprocess.run(['aws', 'bedrock-runtime', 'converse', '--region', args.region, '--cli-input-json', f'file://{f.name}'],
                           capture_output=True, text=True)
        if r.returncode == 0:
            res = json.loads(r.stdout)
            inp = next(c['toolUse']['input'] for c in res['output']['message']['content'] if 'toolUse' in c)
            return inp, (res['usage']['inputTokens'], res['usage']['outputTokens'])
        print(f'  재시도 {attempt + 1}: {r.stderr.strip()[:160]}', file=sys.stderr)
        time.sleep(10 * 2 ** attempt)
    raise RuntimeError('Bedrock 호출 실패')


def as_list(v):  # 모델이 배열을 JSON 문자열로 줄 때
    if isinstance(v, str):
        try: v = json.loads(v)
        except ValueError: return None
    return v if isinstance(v, list) else None


tok = {HAIKU: [0, 0], SONNET: [0, 0]}
def add(m, t): tok[m][0] += t[0]; tok[m][1] += t[1]


def judge(query, desc, reason):
    for _ in range(3):
        j, t = converse(SONNET, JUDGE_SYSTEM, f"질문: {query}\n\n근거 자료:\n{desc or '(없음)'}\n\n추천 이유:\n{reason}", 'record_verdict', JUDGE_SCHEMA, 800)
        add(SONNET, t)
        s = as_list(j.get('sentences'))
        if s is not None and j.get('fit') in ('GOOD', 'OK', 'BAD'):
            return [x for x in s if isinstance(x, dict)], j['fit']
    return [], 'BAD'


mv = q("SELECT row_to_json(x) FROM (SELECT id FROM map_version WHERE active) x")
mv = mv[0]['id'] if mv else 0
CACHE = args.out + '.partial.jsonl'
os.makedirs(os.path.dirname(CACHE), exist_ok=True)
done = {}
if os.path.exists(CACHE):
    for l in open(CACHE):
        r = json.loads(l); done[r['row']['query']] = r
rows = []
for i, query in enumerate(QUERIES, 1):
    if query in done:
        r = done[query]; rows.append(r['row'])
        for m in tok: tok[m][0] += r['tok'][m][0]; tok[m][1] += r['tok'][m][1]
        continue
    t0 = {m: list(v) for m, v in tok.items()}
    vec = '[' + ','.join(f'{x:.7f}' for x in embed(query)) + ']'
    on_map = f"AND EXISTS (SELECT 1 FROM artist_position ap WHERE ap.artist_id = v.artist_id AND ap.map_version = {mv})" if mv else ''
    hits = q(f"""SELECT row_to_json(x) FROM (SELECT v.artist_id AS id, v.descr <=> '{vec}'::vector AS d
      FROM artist_vector v JOIN artist_profile p ON p.artist_id = v.artist_id
      WHERE v.descr IS NOT NULL AND p.description IS NOT NULL AND cardinality(p.evidence) > 0 {on_map}
      ORDER BY v.descr <=> '{vec}'::vector LIMIT 10) x""")
    ids = [h['id'] for h in hits]
    cands = {c['id']: c for c in q(f"""SELECT row_to_json(x) FROM (SELECT a.id, a.name, p.description, p.evidence FROM artist a
      JOIN artist_profile p ON p.artist_id = a.id WHERE a.id IN ({','.join(map(str, ids)) or 0})) x""")}
    row = {'query': query, 'top1': round(hits[0]['d'], 3) if hits else None, 'notFound': not hits or hits[0]['d'] > args.max_distance}
    # RAG 켬 (운영 프롬프트, 출력 검증 = VibeGuard와 같은 규칙)
    on_picks = []
    if not row['notFound']:
        user = f"질문: {query}\n후보 가수 (가수 번호, 이름, 음악 설명, 줄 끝 [번호]는 근거 자료 번호):\n" + '\n'.join(
            f"- 가수 {c['id']} · {c['name']}: {c['description'][:400]} {' '.join(f'[{e}]' for e in c['evidence'])}" for c in (cands[x] for x in ids if x in cands))
        j, t = converse(HAIKU, ON_SYSTEM, user, 'record_picks', ON_SCHEMA, 800); add(HAIKU, t)
        allowed = {e for c in cands.values() for e in c['evidence']}
        seen = set()
        for p in as_list(j.get('picks')) or []:
            aid = p.get('artistId') if isinstance(p, dict) else None
            if aid not in cands or aid in seen or len(seen) >= MAX_PICKS: continue
            seen.add(aid)
            ev = [e for e in (p.get('evidence') or []) if e in allowed]
            reason = (p.get('reason') or '').strip()
            on_picks.append({'id': aid, 'name': cands[aid]['name'], 'reason': reason if reason and ev else None, 'rank': ids.index(aid) + 1})
        for p in on_picks:
            if p['reason']:
                p['sentences'], p['fit'] = judge(query, cands[p['id']]['description'], p['reason'])
    row['on'] = on_picks
    # RAG 끔
    j, t = converse(HAIKU, OFF_SYSTEM, f'질문: {query}', 'record_picks', OFF_SCHEMA, 800); add(HAIKU, t)
    off_picks = []
    for p in (as_list(j.get('picks')) or [])[:MAX_PICKS]:
        if not isinstance(p, dict) or not p.get('name'): continue
        name = p['name'].replace("'", "''")
        found = q(f"""SELECT row_to_json(x) FROM (SELECT a.id, p.description FROM artist a LEFT JOIN artist_profile p ON p.artist_id = a.id
          WHERE lower(a.name) = lower('{name}') ORDER BY p.description IS NULL LIMIT 1) x""")
        desc = found[0]['description'] if found else None
        s, fit = judge(query, desc, p.get('reason', ''))
        if not desc: s = [{**x, 'verdict': 'UNSUPPORTED'} for x in s]
        off_picks.append({'name': p['name'], 'inDb': bool(found), 'hasDesc': bool(desc), 'reason': p.get('reason', ''), 'sentences': s, 'fit': fit})
    row['off'] = off_picks
    rows.append(row)
    with open(CACHE, 'a') as cf:
        cf.write(json.dumps({'row': row, 'tok': {m: [tok[m][0] - t0[m][0], tok[m][1] - t0[m][1]] for m in tok}}, ensure_ascii=False) + '\n')
    print(f"{i:2}/{len(QUERIES)} {query} · top1 {row['top1']} · 켬 {len(on_picks)}명 · 끔 {len(off_picks)}명(DB {sum(p['inDb'] for p in off_picks)})")


def agg(mode):
    picks = [p for r in rows for p in r[mode] if p.get('sentences') is not None or mode == 'off']
    sents = [s for p in picks for s in p.get('sentences', [])]
    c = {v: sum(s['verdict'] == v for s in sents) for v in ['SUPPORTED', 'UNSUPPORTED', 'CONTRADICTED']}
    fit = {v: sum(p.get('fit') == v for p in picks) for v in ['GOOD', 'OK', 'BAD']}
    return len(picks), len(sents), c, fit


cost = lambda m: (tok[m][0] * PRICE[m][0] + tok[m][1] * PRICE[m][1]) / 1e6
lines = [f"질문 {len(rows)}개 · notFound {sum(r['notFound'] for r in rows)}개 · top-1 거리 최대 {max((r['top1'] or 0) for r in rows)}"]
for m, label in [('on', 'RAG 켬 (검색 top-10 + 설명 근거)'), ('off', 'RAG 끔 (질문만)')]:
    n, ns, c, fit = agg(m)
    lines.append(f"{label}: 이유 있는 추천 {n}명 · 근거로 뒷받침되는 문장 {c['SUPPORTED']}/{ns} ({round(c['SUPPORTED'] / max(ns, 1) * 100)}%) · 근거에 없음 {c['UNSUPPORTED']} · 어긋남 {c['CONTRADICTED']} · 적합 GOOD {fit['GOOD']} OK {fit['OK']} BAD {fit['BAD']}")
on_all = [p for r in rows for p in r['on']]
off_all = [p for r in rows for p in r['off']]
lines.append(f"켬: 고른 가수 {len(on_all)}명 중 이유가 검증 통과 {sum(1 for p in on_all if p['reason'])}명 · 검색 순위 평균 {round(sum(p['rank'] for p in on_all) / max(len(on_all), 1), 1)}")
lines.append(f"끔: 추천 {len(off_all)}명 중 우리 DB에 있음 {sum(p['inDb'] for p in off_all)}명 · 설명(근거) 있음 {sum(p['hasDesc'] for p in off_all)}명")
lines.append(f"토큰 Haiku {tok[HAIKU]} (약 ${cost(HAIKU):.3f}) · Sonnet 심판 {tok[SONNET]} (약 ${cost(SONNET):.3f}) · 합계 약 ${cost(HAIKU) + cost(SONNET):.2f}")
print('\n' + '\n'.join(lines))
json.dump({'summary': lines, 'rows': rows}, open(args.out + '.json', 'w'), ensure_ascii=False, indent=1)
open(args.out + '.txt', 'w').write('\n'.join(lines) + '\n')
