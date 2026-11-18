"""가수 설명 충실도: RAG 켬(저장된 설명) vs 끔(근거 없이 다시 생성). Bedrock 비용이 든다 (50명 기준 약 $0.6~0.8, 추정)
심판 Sonnet 4.6이 그 가수의 근거 조각과 설명을 함께 보고 문장마다 SUPPORTED / UNSUPPORTED / CONTRADICTED 판정.
  python3 tools/artist-eval/eval.py [--per-country 17] [--out tools/results/artist-eval]
로컬 postgres 컨테이너(gyeol-local-postgres-1)와 AWS CLI(Bedrock 권한)가 필요하다.
"""
import argparse, json, re, subprocess, sys, tempfile, time

ap = argparse.ArgumentParser()
ap.add_argument('--per-country', type=int, default=17)
ap.add_argument('--out', default='tools/results/artist-eval')
ap.add_argument('--region', default='ap-northeast-2')
args = ap.parse_args()

HAIKU = 'global.anthropic.claude-haiku-4-5-20251001-v1:0'
SONNET = 'global.anthropic.claude-sonnet-4-6'
PRICE = {HAIKU: (1, 5), SONNET: (3, 15)}  # 100만 토큰당 USD (공개 단가, 추정)

# 운영 프롬프트 (MapLlm.DESCRIBE_SYSTEM)와 같은 제약, 근거 관련 줄만 뺌
OFF_SYSTEM = """너는 음악 지식 편집자다. 가수의 음악을 한국어로 설명한다.
나라, 국적, 도시, 언어, 출신지는 절대 언급하지 않는다. 가수 이름과 다른 가수 이름도 쓰지 않는다.
장르와 세부 장르, 분위기, 악기 편성, 템포와 에너지, 보컬 특징, 시대적 감성을 중심으로 3~4문장으로 쓴다.
모든 문장은 부드러운 존댓말(해요체)로 끝낸다."""
OFF_SCHEMA = {'type': 'object', 'required': ['description'], 'properties': {'description': {'type': 'string'}}}

JUDGE_SYSTEM = """너는 사실 검증 심판이다. 가수에 대한 근거 자료와 한국어 설명이 주어진다.
설명을 문장 단위로 나누고, 문장마다 근거 자료로 뒷받침되는지 판정한다.
SUPPORTED: 문장의 핵심 주장(장르·분위기·악기·보컬·시대 등)이 근거 자료에 있거나 근거에서 바로 도출된다.
UNSUPPORTED: 근거 자료에 그 내용이 없다 (사실일 수도 있지만 근거로 확인할 수 없음).
CONTRADICTED: 근거 자료와 어긋난다.
너의 배경지식으로 판단하지 말고 오직 근거 자료만 기준으로 판정한다."""
JUDGE_SCHEMA = {'type': 'object', 'required': ['sentences'], 'properties': {'sentences': {'type': 'array', 'items': {
    'type': 'object', 'required': ['text', 'verdict'],
    'properties': {'text': {'type': 'string'}, 'verdict': {'type': 'string', 'enum': ['SUPPORTED', 'UNSUPPORTED', 'CONTRADICTED']}}}}}}


def q(sql):
    out = subprocess.run(['docker', 'exec', 'gyeol-local-postgres-1', 'psql', '-U', 'gyeol', '-d', 'gyeol', '-tAc', sql],
                         check=True, capture_output=True, text=True).stdout
    return [json.loads(l) for l in out.splitlines() if l.strip()]


def converse(model, system, user, tool, schema, max_tokens):
    req = {'modelId': model, 'system': [{'text': system}],
           'messages': [{'role': 'user', 'content': [{'text': user}]}],
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
            u = res['usage']
            return inp, (u['inputTokens'], u['outputTokens']), res['metrics']['latencyMs']
        print(f'  재시도 {attempt + 1}: {r.stderr.strip()[:160]}', file=sys.stderr)
        time.sleep(10 * 2 ** attempt)
    raise RuntimeError('Bedrock 호출 실패')


# 나라별(한·일·미) 같은 수, 무작위지만 다시 돌려도 같은 표본 (md5 순서)
artists = q(f"""
SELECT row_to_json(x) FROM (
  SELECT a.id, a.mbid::text AS mbid, a.name, p.seed_country AS country, p.description,
         row_number() OVER (PARTITION BY p.seed_country ORDER BY md5(a.id::text)) AS rn
  FROM artist_profile p JOIN artist a ON a.id = p.artist_id
  WHERE p.model LIKE '%haiku%' AND p.description IS NOT NULL AND p.seed_country IS NOT NULL
) x WHERE rn <= {args.per_country} ORDER BY country, rn""")
print(f'표본 {len(artists)}명', {c: sum(1 for a in artists if a['country'] == c) for c in {a['country'] for a in artists}})

import os
tok = {HAIKU: [0, 0], SONNET: [0, 0]}
rows = []
CACHE = args.out + '.partial.jsonl'  # 가수별 결과를 바로 저장해 멈춰도 이어서 돈다 (같은 표본이면 Bedrock을 다시 부르지 않음)
done = {}
if os.path.exists(CACHE):
    for l in open(CACHE):
        r = json.loads(l); done[r['row']['name']] = r
for i, a in enumerate(artists, 1):
    if a['name'] in done:
        r = done[a['name']]; rows.append(r['row'])
        for m in tok: tok[m][0] += r['tok'][m][0]; tok[m][1] += r['tok'][m][1]
        continue
    t0 = {m: list(v) for m, v in tok.items()}
    chunks = q(f"SELECT row_to_json(c) FROM (SELECT id, source, lang, text FROM knowledge_chunk WHERE entity_type = 'ARTIST' AND entity_mbid = '{a['mbid']}' ORDER BY id) c")
    evidence = '\n'.join(f"[{c['id']}] ({c['source']}, {c['lang']}) {c['text'][:900]}" for c in chunks)
    off, t, _ = converse(HAIKU, OFF_SYSTEM, f"가수: {a['name']}", 'record_artist', OFF_SCHEMA, 800)
    tok[HAIKU][0] += t[0]; tok[HAIKU][1] += t[1]
    row = {'name': a['name'], 'country': a['country'], 'chunks': len(chunks)}
    for mode, text in [('on', a['description']), ('off', off.get('description', ''))]:
        for _ in range(3):  # 심판이 배열을 (가끔 깨진) JSON 문자열로 돌려주면 다시 묻는다
            j, t, _ = converse(SONNET, JUDGE_SYSTEM, f"근거 자료:\n{evidence}\n\n설명:\n{text}", 'record_verdicts', JUDGE_SCHEMA, 1500)
            tok[SONNET][0] += t[0]; tok[SONNET][1] += t[1]
            sents = j.get('sentences', [])
            if isinstance(sents, str):
                try: sents = json.loads(sents)
                except ValueError: continue
            break
        row[mode] = {'text': text, 'sentences': [x for x in sents if isinstance(x, dict)]}
    rows.append(row)
    with open(CACHE, 'a') as cf:
        cf.write(json.dumps({'row': row, 'tok': {m: [tok[m][0] - t0[m][0], tok[m][1] - t0[m][1]] for m in tok}}, ensure_ascii=False) + '\n')
    s = lambda m: sum(x['verdict'] == 'SUPPORTED' for x in row[m]['sentences'])
    print(f"{i:2}/{len(artists)} {a['country']} {a['name']}: 켬 {s('on')}/{len(row['on']['sentences'])} · 끔 {s('off')}/{len(row['off']['sentences'])}")

def agg(m):
    sents = [x for r in rows for x in r[m]['sentences']]
    c = {v: sum(x['verdict'] == v for x in sents) for v in ['SUPPORTED', 'UNSUPPORTED', 'CONTRADICTED']}
    full = sum(1 for r in rows if r[m]['sentences'] and all(x['verdict'] == 'SUPPORTED' for x in r[m]['sentences']))
    return len(sents), c, full

cost = lambda m: (tok[m][0] * PRICE[m][0] + tok[m][1] * PRICE[m][1]) / 1e6
lines = []
for m, label in [('on', 'RAG 켬 (운영 설명)'), ('off', 'RAG 끔 (근거 없이 Haiku)')]:
    n, c, full = agg(m)
    lines.append(f"{label}: 근거로 뒷받침되는 문장 {c['SUPPORTED']}/{n} ({round(c['SUPPORTED'] / n * 100)}%) · 근거에 없음 {c['UNSUPPORTED']} · 근거와 어긋남 {c['CONTRADICTED']} · 모든 문장이 뒷받침된 가수 {full}/{len(rows)}")
lines.append(f"토큰 Haiku {tok[HAIKU]} (약 ${cost(HAIKU):.3f}) · Sonnet 심판 {tok[SONNET]} (약 ${cost(SONNET):.3f}) · 합계 약 ${cost(HAIKU) + cost(SONNET):.2f}")
print('\n' + '\n'.join(lines))
json.dump({'summary': lines, 'rows': rows}, open(args.out + '.json', 'w'), ensure_ascii=False, indent=1)
open(args.out + '.txt', 'w').write('\n'.join(lines) + '\n')
