// 곡 설명 RAG 켬/끔 비교 (docs/l3-design.md 9장). Bedrock 비용이 든다 (41곡 기준 약 $1, 추정)
// 측정 모드 worker: LLM_MODE=bedrock LLM_DEBUG_ENDPOINT=true WORKER_ROLE=none CLEANUP_ENABLED=false PORT=18083
//   node tools/describe-eval/eval.mjs [--worker http://localhost:18083] [--limit 41]
import { execFileSync } from 'node:child_process';
const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const WORKER = arg('--worker', 'http://localhost:18083');
const LIMIT = Number(arg('--limit', '100'));
// Bedrock이 큰 요청(RAG 켬, 묶음당 입력 수천 토큰)에 503을 자주 줘서 측정은 작은 묶음으로 (운영은 SQS 재시도로 처리)
const BATCH = Number(arg('--batch', '2'));
const MODES = arg('--rag', 'both') === 'both' ? [true, false] : [arg('--rag') === 'on'];
// 단가 (100만 토큰당 USD, 추정): Haiku 4.5 [1, 5], Sonnet 4.6 [3, 15]
const cost = (t, p) => (t[0] * p[0] + t[1] * p[1]) / 1e6;
const ids = execFileSync('docker', ['exec', 'gyeol-local-postgres-1', 'psql', '-U', 'gyeol', '-d', 'gyeol', '-tAc',
  `select t.id from track t where exists (select 1 from knowledge_chunk c join artist a on a.id = t.artist_id where c.entity_mbid in (t.recording_mbid, t.release_group_mbid, a.mbid) or c.entity_mbid = any(t.work_mbids)) order by t.id limit ${LIMIT}`]).toString().trim().split('\n').map(Number);

const FIELDS = ['genres', 'moods', 'tempo', 'energy', 'vocal', 'summary'];
async function run(rag) {
  const s = { claimed: 0, supported: 0, empty: 0, slots: 0, byField: {}, byConf: {}, dTok: [0, 0], jTok: [0, 0], ms: 0, samples: [], tracks: 0, failed: 0, batches: 0 };
  for (let i = 0; i < ids.length; i += BATCH) {
    let r;
    for (let a = 0; a < 4 && !r?.tracks; a++) {
      if (a) await new Promise(x => setTimeout(x, 10000 * 2 ** (a - 1)));
      r = await fetch(`${WORKER}/internal/describe/eval`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ trackIds: ids.slice(i, i + BATCH), rag }) }).then(x => x.json()).catch(() => null);
    }
    if (!r?.tracks) { s.failed += Math.min(BATCH, ids.length - i); console.error(`묶음 ${i / BATCH + 1} 실패`); continue; }
    s.batches++; s.tracks += r.tracks.length;
    s.dTok[0] += r.describeTokens[0]; s.dTok[1] += r.describeTokens[1]; s.jTok[0] += r.judgeTokens[0]; s.jTok[1] += r.judgeTokens[1]; s.ms += r.describeMs;
    for (const t of r.tracks) {
      for (const f of FIELDS) {
        const value = f === 'summary' ? t.summary : t.fields[f]?.value;
        const has = value != null && !(Array.isArray(value) && value.length === 0);
        s.slots++;
        if (!has) { s.empty++; continue; }
        s.claimed++;
        const ok = t.verdicts[f] === 'SUPPORTED';
        if (ok) s.supported++;
        (s.byField[f] ??= [0, 0])[1]++; if (ok) s.byField[f][0]++;
        const c = f === 'summary' ? 'SUMMARY' : t.fields[f].confidence;
        (s.byConf[c] ??= [0, 0])[1]++; if (ok) s.byConf[c][0]++;
      }
      if (s.samples.length < 3) s.samples.push(`${t.artist} - ${t.title}: ${t.summary ?? '(근거 부족)'} | 장르 ${JSON.stringify(t.fields.genres?.value)} (${t.fields.genres?.confidence})`);
    }
  }
  return s;
}

const pct = (a, b) => b ? `${a}/${b} (${Math.round(a / b * 100)}%)` : '-';
for (const rag of MODES) {
  const s = await run(rag);
  console.log(`\n== RAG ${rag ? '켬' : '끔'} (대상 ${ids.length}곡, 집계 ${s.tracks}곡, 실패로 빠진 ${s.failed}곡)`);
  console.log(`근거로 뒷받침되는 칸 ${pct(s.supported, s.claimed)} · 채운 칸 ${pct(s.claimed, s.slots)} · 비운 칸(근거 부족) ${pct(s.empty, s.slots)}`);
  console.log('칸별', Object.entries(s.byField).map(([k, [a, b]]) => `${k} ${pct(a, b)}`).join(', '));
  if (rag) console.log('신뢰도별', Object.entries(s.byConf).map(([k, [a, b]]) => `${k} ${pct(a, b)}`).join(', '));
  console.log(`곡 설명 토큰 ${s.dTok.join('/')} (약 $${cost(s.dTok, [1, 5]).toFixed(3)}, 곡당 $${(cost(s.dTok, [1, 5]) / ids.length).toFixed(4)}) · 심판 토큰 ${s.jTok.join('/')} (약 $${cost(s.jTok, [3, 15]).toFixed(3)}) · 묶음(${BATCH}곡)당 ${Math.round(s.ms / Math.max(1, s.batches))}ms`);
  s.samples.forEach(x => console.log('  예:', x));
}
