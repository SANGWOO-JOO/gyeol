// LLM 제목 정리 정확도·비용 비교 (docs/l2-design.md 10장)
// worker를 측정 모드로 띄운 뒤 (LLM_MODE=bedrock LLM_DEBUG_ENDPOINT=true WORKER_ROLE=none CLEANUP_ENABLED=false)
//   node tools/llm-eval/eval.mjs --model fake                                              # 규칙만 (비용 없음)
//   node tools/llm-eval/eval.mjs --model global.anthropic.claude-haiku-4-5-20251001-v1:0   # Bedrock 비용 발생
// 실제 운영과 같이 10개씩 묶어 보낸다.
import fs from 'node:fs';
const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const WORKER = arg('--worker', 'http://localhost:18082');
const MODEL = arg('--model', 'fake');
const BATCH = Number(arg('--batch', '10'));
const { cases } = JSON.parse(fs.readFileSync(arg('--samples', 'tools/llm-eval/samples.json'), 'utf8'));
// 단가 (100만 토큰당 USD, 추정): Haiku 4.5는 Anthropic 공개 가격. 그 밖은 확인 전이라 비용을 계산하지 않는다
const PRICE = { 'haiku-4-5': [1, 5] };
const price = Object.entries(PRICE).find(([k]) => MODEL.includes(k))?.[1];

const norm = s => (s || '').toLowerCase().normalize('NFKC').replace(/[\s\p{P}\p{S}]/gu, '');
const IDENT = new Set(['ORIGINAL', 'LIVE', 'REMIX']);
const any = (want, got) => want.length === 0 || want.some(w => got.some(g => g && norm(g) === norm(w)));

let inTok = 0, outTok = 0, ms = 0; const rows = []; const score = { version: 0, decision: 0, fields: 0, fieldsN: 0, n: 0 };
for (let i = 0; i < cases.length; i += BATCH) {
  const batch = cases.slice(i, i + BATCH);
  const items = batch.map((c, j) => ({ index: j, title: c.title, channel: c.channel }));
  const t0 = performance.now();
  const r = await fetch(`${WORKER}/internal/llm/clean`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model: MODEL, items }) }).then(x => x.json());
  ms += performance.now() - t0; inTok += r.raw.inputTokens; outTok += r.raw.outputTokens;
  batch.forEach((c, j) => {
    const raw = r.raw.items.find(x => x.index === j);
    const ok = r.accepted[r.raw.items.indexOf(raw)];
    const sure = c.sure !== false;
    const vOk = raw?.version === c.version;
    const decisionOk = IDENT.has(raw?.version) === IDENT.has(c.version);
    // 식별할 곡: 검증을 통과하고 가수·곡이 정답 표기 중 하나와 같아야 한다
    const fieldsOk = IDENT.has(c.version) ? !!ok && any(c.artist, [ok.artist, ok.artistAlt]) && any(c.song, [ok.title, ok.titleAlt]) : null;
    if (sure) {
      score.n++; if (vOk) score.version++; if (decisionOk) score.decision++;
      if (fieldsOk !== null) { score.fieldsN++; if (fieldsOk) score.fields++; }
    }
    rows.push({ 정답: c.version, 판정: raw?.version ?? '-', 가수: ok?.artist ?? raw?.artist ?? '', 곡: ok?.title ?? raw?.title ?? '', 검증: ok ? '통과' : (IDENT.has(raw?.version) ? '거절' : ''), 결과: !sure ? '확인 필요' : (fieldsOk === null ? (decisionOk ? 'O' : 'X') : (fieldsOk && vOk ? 'O' : fieldsOk ? '△' : 'X')) });
  });
}
console.table(rows);
const pct = (a, b) => `${a}/${b} (${Math.round(a / b * 100)}%)`;
console.log(`모델 ${MODEL}`);
console.log(`식별 여부 결정 ${pct(score.decision, score.n)} · 분류 정확 ${pct(score.version, score.n)} · 식별할 곡의 가수·곡 정답 ${pct(score.fields, score.fieldsN)}`);
console.log(`토큰 입력 ${inTok} · 출력 ${outTok} · 묶음당 평균 ${Math.round(ms / Math.ceil(cases.length / BATCH))}ms` + (price ? ` · 비용 약 $${((inTok * price[0] + outTok * price[1]) / 1e6).toFixed(4)} (곡당 $${((inTok * price[0] + outTok * price[1]) / 1e6 / cases.length).toFixed(5)})` : ''));
