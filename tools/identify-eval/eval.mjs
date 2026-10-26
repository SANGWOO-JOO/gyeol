// 곡 식별 정확도 측정: external-fetcher /internal/musicbrainz/identify 를 표본으로 부른다 (docs/l2-design.md 6장)
//   node tools/identify-eval/eval.mjs [--fetcher http://localhost:18081] [--samples tools/cover-art/samples.json]
// 표본은 앨범 표지 측정과 같은 파일. 가수는 "한글 이름 - Topic" 채널에서 온 것처럼 한글 이름으로만 묻는다.
import fs from 'node:fs';
const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const FETCHER = arg('--fetcher', 'http://localhost:18081');
const { cases } = JSON.parse(fs.readFileSync(arg('--samples', 'tools/cover-art/samples.json'), 'utf8'));
const norm = s => (s || '').toLowerCase().normalize('NFKC').replace(/[\s()\[\]~～\-'’.,!?:&]/g, '');

const rows = []; let scored = 0, hit = 0, found = 0, calls = 0, ms = 0;
for (const c of cases) {
  const t0 = Date.now();
  let r;
  for (let a = 0; a < 3; a++) {
    const res = await fetch(`${FETCHER}/internal/musicbrainz/identify`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ artist: c.ko, title: c.title }) });
    if (res.ok) { r = await res.json(); break; }
    r = { status: `HTTP ${res.status}` }; await new Promise(x => setTimeout(x, 3000));
  }
  const ok = r.status === 'MATCHED' && c.album.some(k => norm(r.release?.title).includes(norm(k)));
  if (r.status === 'MATCHED') found++;
  if (c.sure) { scored++; if (ok) hit++; }
  calls += r.calls || 0; ms += Date.now() - t0;
  rows.push({ 곡: `${c.ko} - ${c.title}`, 결과: r.status, 가수: r.artist?.name ?? r.reason ?? '', 음반: r.release?.title ?? '', 발매: r.release?.date ?? '', 판정: c.sure ? (ok ? 'O' : 'X') : '확인 필요', 호출: r.calls ?? '', 초: Math.round((Date.now() - t0) / 100) / 10 });
}
console.table(rows);
console.log(`찾음 ${found}/${cases.length}, 정답이 확실한 ${scored}곡 중 음반 일치 ${hit}, 곡당 평균 ${(ms / cases.length / 1000).toFixed(1)}초·호출 ${(calls / cases.length).toFixed(1)}번`);
