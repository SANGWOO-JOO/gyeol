// 곡 고르기 검색 정확도: api /api/tracks/search 를 L1 질의 표본으로 부른다 (docs/l2-design.md 9장)
//   node tools/search-eval/eval.mjs [--api http://localhost:18000] [--samples backend/external-fetcher/src/test/resources/lastfm-samples.json]
// 원곡 판정은 표본의 title·artist 키워드(공백·문장부호 무시)로 한다. 결과 1위가 원곡이면 성공.
import fs from 'node:fs';
const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const API = arg('--api', 'http://localhost:18000');
const { cases } = JSON.parse(fs.readFileSync(arg('--samples', 'backend/external-fetcher/src/test/resources/lastfm-samples.json'), 'utf8'));
const norm = s => (s || '').toLowerCase().normalize('NFKC').replace(/[\s\p{P}\p{S}]/gu, '');
const isOriginal = (c, it) => c.title.some(t => norm(it.title).includes(norm(t))) && c.artist.some(a => norm(it.artist).includes(norm(a)));

const rows = []; const byType = {}; let top1 = 0; const ms = [];
for (const c of cases) {
  const t0 = performance.now();
  const r = await fetch(`${API}/api/tracks/search?q=${encodeURIComponent(c.q)}&limit=10`).then(x => x.json()).catch(() => ({ items: [], source: 'error' }));
  ms.push(performance.now() - t0);
  const rank = (r.items || []).findIndex(it => isOriginal(c, it)) + 1;
  const ok = rank === 1;
  if (ok) top1++;
  (byType[c.type] ??= [0, 0])[1]++; if (ok) byType[c.type][0]++;
  const first = r.items?.[0];
  rows.push({ 유형: c.type, 질의: c.q, 순위: rank || '-', 출처: r.source, '1위': first ? `${first.title} · ${first.artist}${first.trackId ? ' (DB)' : ''}` : '' });
}
console.table(rows);
ms.sort((a, b) => a - b);
console.log(`원곡 1위 ${top1}/${cases.length} (${Math.round(top1 / cases.length * 100)}%), 응답 중앙값 ${Math.round(ms[ms.length >> 1])}ms`);
console.log('유형별', Object.entries(byType).map(([k, [a, b]]) => `${k} ${a}/${b}`).join(', '));
