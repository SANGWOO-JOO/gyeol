"""코드 맞추기 이유: 근거 고르기 전후 비교 (docs/todo/codematch-vector-rag.md 7절). Bedrock 비용이 든다 (30쌍 약 $1, 추정)
지도 가수로 가상의 두 사람을 만들고, 같은 Haiku·같은 프롬프트(BedrockCodeMatchLlm)로
  before: 겹친 가수, 없으면 각자 첫 가수 2명씩 (기존 top 2와 같은 역할)
  after : 겹친 가수 먼저 + 벡터가 가까운 짝 3개 (CodeMatchRepository.nearestPairs와 같은 쿼리)
를 비교한다. 심판 Sonnet 4.6이 붙인 가수 설명만 보고 문장마다 SUPPORTED/UNSUPPORTED/CONTRADICTED, 이유가 두 사람을 잇는지 판정.
  python3 tools/codematch-eval/eval.py [--pairs 30] [--out tools/results/codematch-eval]
"""
import argparse, json, os, random, subprocess, sys, tempfile, time

ap = argparse.ArgumentParser()
ap.add_argument('--pairs', type=int, default=30)
ap.add_argument('--per-person', type=int, default=12)
ap.add_argument('--out', default='tools/results/codematch-eval')
ap.add_argument('--region', default='ap-northeast-2')
ap.add_argument('--seed', type=int, default=7)
args = ap.parse_args()

HAIKU = 'global.anthropic.claude-haiku-4-5-20251001-v1:0'
SONNET = 'global.anthropic.claude-sonnet-4-6'
PRICE = {HAIKU: (1, 5), SONNET: (3, 15)}

# BedrockCodeMatchLlm.SYSTEM 과 같게
REASON_SYSTEM = """너는 음악 방의 "코드 맞추기" 결과에 짧은 이유를 쓴다. 두 사람의 음악 취향이 어디서 닿고 어디서 갈리는지 한국어 2~3문장으로 쓴다.
주어진 구역 이름과 가수 설명에 있는 내용만 쓴다. 성격·연애·관계를 추측하지 않는다. 가수 이름은 써도 된다.
"음악이 가까운 짝"은 서로 다른 두 가수다. 짝을 두 사람이 함께 좋아한다고 쓰지 말고, 짝의 두 설명에 함께 나오는 장르·분위기·소리만 닮은 점으로 쓴다.
쓴 내용의 근거가 된 설명 줄의 [번호]를 evidence에 적는다. 근거로 쓸 내용이 없으면 reason을 빈 문자열로 둔다."""
REASON_SCHEMA = {'type': 'object', 'required': ['reason', 'evidence'],
                 'properties': {'reason': {'type': 'string'}, 'evidence': {'type': 'array', 'items': {'type': 'integer'}}}}
JUDGE_SYSTEM = """너는 사실 검증 심판이다. 두 사람(A, B)의 음악 취향을 비교한 이유 문장과, 그 근거로 주어진 가수 설명이 있다.
이유를 문장 단위로 나누고, 문장마다 주어진 가수 설명으로 뒷받침되는지 판정한다.
SUPPORTED: 문장의 주장이 설명에 있거나 설명에서 바로 도출된다. UNSUPPORTED: 설명에 없다. CONTRADICTED: 설명과 어긋난다.
connects: 이유가 A의 가수와 B의 가수 사이의 구체적인 공통점(장르·분위기·소리)을 짚으면 true, 각자 따로 설명하거나 일반론이면 false.
너의 배경지식이 아니라 주어진 설명만 기준으로 판정한다."""
JUDGE_SCHEMA = {'type': 'object', 'required': ['sentences', 'connects'], 'properties': {
    'sentences': {'type': 'array', 'items': {'type': 'object', 'required': ['text', 'verdict'], 'properties': {
        'text': {'type': 'string'}, 'verdict': {'type': 'string', 'enum': ['SUPPORTED', 'UNSUPPORTED', 'CONTRADICTED']}}}},
    'connects': {'type': 'boolean'}}}


def q(sql):
    out = subprocess.run(['docker', 'exec', 'gyeol-local-postgres-1', 'psql', '-U', 'gyeol', '-d', 'gyeol', '-tAc', sql],
                         check=True, capture_output=True, text=True).stdout
    return [json.loads(l) for l in out.splitlines() if l.strip()]


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


def as_list(v):
    if isinstance(v, str):
        try: v = json.loads(v)
        except ValueError: return None
    return v


# 근거로 쓸 수 있는 가수 (설명·근거 번호·벡터가 있음)
pool = q("""SELECT row_to_json(x) FROM (
  SELECT a.id, a.name, p.description, p.evidence FROM artist a JOIN artist_profile p ON p.artist_id = a.id
  JOIN artist_vector v ON v.artist_id = a.id WHERE p.description IS NOT NULL AND cardinality(p.evidence) > 0 ORDER BY a.id) x""")
by_id = {a['id']: a for a in pool}
rnd = random.Random(args.seed)


def pairs_sql(a_ids, b_ids, n):
    if not a_ids or not b_ids: return []
    A = ','.join(map(str, a_ids)); B = ','.join(map(str, b_ids))
    return q(f"""SELECT row_to_json(x) FROM (
      SELECT a.artist_id AS a, nb.artist_id AS b, nb.d FROM artist_vector a
      CROSS JOIN LATERAL (SELECT b.artist_id, b.mixed <=> a.mixed AS d FROM artist_vector b
        WHERE b.artist_id IN ({B}) AND b.artist_id <> a.artist_id ORDER BY d LIMIT 1) nb
      WHERE a.artist_id IN ({A}) ORDER BY nb.d LIMIT {n}) x""")


cases = []
for i in range(args.pairs):
    overlap = i % 2 == 1  # 짝수 번째는 겹친 가수 없음, 홀수 번째는 2명 겹침
    people = rnd.sample(list(by_id), args.per_person * 2)
    A, B = people[:args.per_person], people[args.per_person:]
    if overlap: B[:2] = A[:2]
    cases.append({'A': A, 'B': B, 'overlap': overlap})

tok = {HAIKU: [0, 0], SONNET: [0, 0]}
rows = []
for n, c in enumerate(cases, 1):
    A, B = c['A'], c['B']
    shared = [x for x in A if x in B]
    before = (shared or (A[:2] + B[:2]))[:6]
    prs = pairs_sql([x for x in A if x not in shared], [x for x in B if x not in shared], 3)
    after = list(dict.fromkeys(shared[:6] + [i for p in prs for i in (p['a'], p['b'])]))[:6] or before
    pair_names = [(by_id[p['a']]['name'], by_id[p['b']]['name']) for p in prs if p['a'] in after and p['b'] in after]
    row = {'overlap': c['overlap'], 'pair_d': [round(p['d'], 3) for p in prs]}
    for mode, pick, pn in [('before', before, []), ('after', after, pair_names)]:
        arts = [by_id[i] for i in pick]
        lines = ['두 사람: 상우, 민지 (코드 점수 50%)']
        if shared: lines.append('둘 다 담은 가수: ' + ', '.join(by_id[i]['name'] for i in shared))
        if pn: lines.append('음악이 가까운 짝 (서로 다른 가수, 상우의 가수 ↔ 민지의 가수): ' + ', '.join(f'{x} ↔ {y}' for x, y in pn))
        lines.append('가수 설명 (각 줄 끝 [번호]는 근거 자료 번호):')
        lines += [f"- {a['name']}: {a['description'][:400]} " + ' '.join(f'[{e}]' for e in a['evidence']) for a in arts]
        owner = '\n'.join(f"- {by_id[i]['name']}: {'상우' if i in A else ''}{'·' if i in A and i in B else ''}{'민지' if i in B else ''}" for i in pick)
        r, t = converse(HAIKU, REASON_SYSTEM, '\n'.join(lines), 'record_reason', REASON_SCHEMA, 400)
        tok[HAIKU][0] += t[0]; tok[HAIKU][1] += t[1]
        allowed = {e for a in arts for e in a['evidence']}
        ev = [e for e in (as_list(r.get('evidence')) or []) if isinstance(e, int) and e in allowed]
        text = (r.get('reason') or '').strip() if ev else ''
        res = {'pick': [by_id[i]['name'] for i in pick], 'reason': text, 'passed': bool(text)}
        if text:
            evidence = '\n'.join(f"- {a['name']} ({'상우' if a['id'] in A else ''}{'·' if a['id'] in A and a['id'] in B else ''}{'민지' if a['id'] in B else ''}): {a['description']}" for a in arts)
            for _ in range(3):
                j, t = converse(SONNET, JUDGE_SYSTEM, f"가수 설명:\n{evidence}\n\n이유:\n{text}", 'record_verdicts', JUDGE_SCHEMA, 1200)
                tok[SONNET][0] += t[0]; tok[SONNET][1] += t[1]
                s = as_list(j.get('sentences'))
                if s is not None: break
            res['sentences'] = [x for x in (s or []) if isinstance(x, dict)]
            res['connects'] = bool(j.get('connects'))
        row[mode] = res
    rows.append(row)
    f = lambda m: (('통과' if row[m]['passed'] else '탈락') + (' · 잇기' if row[m].get('connects') else ''))
    print(f"{n:2}/{len(cases)} {'겹침' if c['overlap'] else '없음'} 전 {f('before')} | 후 {f('after')} | 짝 거리 {row['pair_d']}")


def agg(mode, overlap=None):
    rs = [r for r in rows if overlap is None or r['overlap'] == overlap]
    passed = [r[mode] for r in rs if r[mode]['passed']]
    sents = [s for p in passed for s in p.get('sentences', [])]
    sup = sum(s['verdict'] == 'SUPPORTED' for s in sents)
    con = sum(s['verdict'] == 'CONTRADICTED' for s in sents)
    return f"이유 나옴 {len(passed)}/{len(rs)} · 근거로 뒷받침되는 문장 {sup}/{len(sents)} ({round(sup / max(1, len(sents)) * 100)}%) · 어긋남 {con} · 두 사람을 잇는 이유 {sum(p.get('connects', False) for p in passed)}/{len(rs)}"

cost = lambda m: (tok[m][0] * PRICE[m][0] + tok[m][1] * PRICE[m][1]) / 1e6
lines = []
for label, ov in [('전체', None), ('겹친 가수 없음', False), ('겹친 가수 있음', True)]:
    lines.append(f"[{label}] 전: {agg('before', ov)}")
    lines.append(f"[{label}] 후: {agg('after', ov)}")
ds = sorted(d for r in rows for d in r['pair_d'])
if ds: lines.append(f"짝 거리 분포 (코사인): 최소 {ds[0]} · 중앙 {ds[len(ds) // 2]} · 최대 {ds[-1]} · {len(ds)}개")
lines.append(f"토큰 Haiku {tok[HAIKU]} (약 ${cost(HAIKU):.3f}) · Sonnet 심판 {tok[SONNET]} (약 ${cost(SONNET):.3f}) · 합계 약 ${cost(HAIKU) + cost(SONNET):.2f}")
print('\n' + '\n'.join(lines))
os.makedirs(os.path.dirname(args.out), exist_ok=True)
json.dump({'summary': lines, 'rows': rows}, open(args.out + '.json', 'w'), ensure_ascii=False, indent=1)
open(args.out + '.txt', 'w').write('\n'.join(lines) + '\n')
