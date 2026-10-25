// 곡 → 원곡이 처음 실린 음반 → 그 음반 표지를 찾는 규칙의 정확도 측정 (L2 곡 식별 준비, docs/l1-design.md 4-3장)
// 의존성 없음 (Node 18+). Last.fm 키는 .env의 LASTFM_API_KEY, 표지 이미지와 결과는 --out 폴더에 저장
//   ( set -a; . ./.env; set +a; node tools/cover-art/measure.mjs --out tools/results/cover-art )
import fs from 'node:fs';
import path from 'node:path';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const OUT = arg('--out', 'tools/results/cover-art');
const SAMPLES = arg('--samples', path.join(path.dirname(new URL(import.meta.url).pathname), 'samples.json'));
const KEY = process.env.LASTFM_API_KEY;
const UA = `gyeol/0.1.0 ( ${process.env.GYEOL_CONTACT || 'https://github.com/SANGWOO-JOO'} )`;
fs.mkdirSync(OUT, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));
const norm = s => (s || '').toLowerCase().normalize('NFKC').replace(/[\s()\[\]~～\-'’.,!?:&]/g, '');

// 한글 → 로마자 (국어의 로마자 표기법, 음운 변화는 무시). MusicBrainz에 로마자로만 등록된 곡 대비 (예: 난춘 → NAN CHUN)
const I = ['g','kk','n','d','tt','r','m','b','pp','s','ss','','j','jj','ch','k','t','p','h'];
const V = ['a','ae','ya','yae','eo','e','yeo','ye','o','wa','wae','oe','yo','u','wo','we','wi','yu','eu','ui','i'];
const F = ['','k','k','k','n','n','n','t','l','l','l','l','l','l','l','l','m','p','p','t','t','ng','t','t','k','t','p','t'];
const roman = s => [...s].map(ch => { const c = ch.charCodeAt(0) - 0xAC00; return c < 0 || c > 11171 ? ch : I[Math.floor(c / 588)] + V[Math.floor(c % 588 / 28)] + F[c % 28]; }).join('');

// 제목 비교: 전체, 괄호 앞뒤, "/" 앞뒤, 로마자 (예: "뚜두뚜두 (DDU-DU DDU-DU)", "Everything / 걱정하지마")
const parts = s => new Set([s, ...s.split(/\s*[\/(（]\s*|\s*[)）]\s*/)].map(norm).filter(Boolean));
const sameTitle = (a, t) => { const p = parts(a); return p.has(norm(t)) || (/[가-힣]/.test(t) && p.has(norm(roman(t)))); };

const EXCLUDED = ['Compilation', 'Live', 'Remix', 'DJ-mix', 'Mixtape/Street', 'Demo'];
const VARIANT = /ver\.|version|inst|remix|acoustic|mr\b|karaoke/i;

// MusicBrainz: 초당 1회, 실패하면 늦춰 가며 다시 시도
let last = 0;
async function mb(p) {
  for (let a = 0; a < 5; a++) {
    const w = 1100 - (Date.now() - last); if (w > 0) await sleep(w); last = Date.now();
    const r = await fetch(`https://musicbrainz.org/ws/2/${p}`, { headers: { 'User-Agent': UA, Accept: 'application/json' } });
    if (r.ok) return r.json();
    await sleep(2000 * (a + 1));
  }
  throw new Error('MusicBrainz 재시도 초과');
}
async function cover(kind, id) {
  for (let a = 0; a < 3; a++) {
    const r = await fetch(`https://coverartarchive.org/${kind}/${id}/front-250`, { headers: { 'User-Agent': UA } });
    if (r.ok) return Buffer.from(await r.arrayBuffer());
    if (r.status === 404) return null;
    await sleep(2000);
  }
  return null;
}

// 1) 가수 확정: 이름·정렬 이름·별칭이 한글 또는 영문 이름과 정확히 같은 가수만 (동명 가수 배제)
async function artistIds(ko, en) {
  const d = await mb(`artist?fmt=json&limit=10&query=${encodeURIComponent(`artist:"${ko}" OR alias:"${ko}" OR artist:"${en}" OR alias:"${en}"`)}`);
  const ok = [];
  for (const a of d.artists || []) {
    const names = [a.name, a['sort-name'], ...(a.aliases || []).map(x => x.name)].map(norm);
    const k = names.includes(norm(ko)), e = names.includes(norm(en));
    if (k || e) ok.push({ id: a.id, name: a.name, rank: (k && e ? 3 : k ? 2 : 1) * 1000 + (a.score || 0) });
  }
  return ok.sort((x, y) => y.rank - x.rank).slice(0, 3);
}

// Last.fm이 아는 표기 (띄어쓰기 등). 예: 좋은날 → 좋은 날
async function lastfmTitle(t, ko, en) {
  if (!KEY) return null;
  for (const a of [ko, en]) {
    await sleep(260);
    const s = await (await fetch(`https://ws.audioscrobbler.com/2.0/?format=json&method=track.search&limit=10&api_key=${KEY}&artist=${encodeURIComponent(a)}&track=${encodeURIComponent(t)}`)).json();
    for (const x of s.results?.trackmatches?.track || []) if (norm(x.name) === norm(t) && x.name !== t) return x.name;
  }
  return null;
}

// 2) 녹음 찾기: 제목 검색 → Last.fm 표기로 검색 → 가수 녹음 목록 넓게 훑기
async function recordings(arid, t, alt) {
  for (const tt of [t, alt].filter(Boolean)) {
    const out = [];
    for (let off = 0; off < 300; off += 100) {
      const d = await mb(`recording?fmt=json&limit=100&offset=${off}&query=${encodeURIComponent(`arid:${arid} AND recording:(${tt.replace(/[()\[\]\/:"!?~^\\-]/g, ' ')})`)}`);
      out.push(...(d.recordings || []).filter(r => sameTitle(r.title, t)));
      if ((d.recordings || []).length < 100) break;
    }
    if (out.length) return { recs: out, how: tt === t ? '제목' : 'Last.fm 표기' };
  }
  const out = [];
  for (let off = 0; off < 1000; off += 100) {
    const d = await mb(`recording?fmt=json&limit=100&offset=${off}&query=${encodeURIComponent(`arid:${arid}`)}`);
    out.push(...(d.recordings || []).filter(r => sameTitle(r.title, t)));
    if ((d.recordings || []).length < 100) break;
  }
  return { recs: out, how: '가수 녹음 훑기' };
}

// 3) 음반: 공식, 컴필레이션·라이브·리믹스 제외, 버전 음반은 뒤로, 가장 이른 것
// 4) 표지: 그 음반 → 같은 음반(같은 제목)의 다른 판. 다른 음반 표지로 대신하지 않음
async function find({ title: t, ko, en }) {
  const arts = await artistIds(ko, en);
  if (!arts.length) return { album: '(가수 못 찾음)' };
  let alt;
  for (const a of arts) {
    let { recs, how } = await recordings(a.id, t, alt);
    if (!recs.length && alt === undefined) { alt = await lastfmTitle(t, ko, en); if (alt) ({ recs, how } = await recordings(a.id, t, alt)); }
    const rels = recs.flatMap(r => r.releases || [])
      .filter(r => r.status === 'Official' && r.date && !(r['release-group']?.['secondary-types'] || []).some(x => EXCLUDED.includes(x)));
    rels.sort((x, y) => (VARIANT.test(x.title) - VARIANT.test(y.title)) || x.date.padEnd(10, '9').localeCompare(y.date.padEnd(10, '9')));
    const seen = new Set();
    for (const r of rels) {
      const rg = r['release-group'].id; if (seen.has(rg)) continue; seen.add(rg);
      let img = await cover('release', r.id), from = '음반';
      if (!img) {
        const b = await mb(`release?fmt=json&limit=50&release-group=${rg}`);
        for (const x of (b.releases || []).filter(x => x.id !== r.id && norm(x.title) === norm(r.title) && x['cover-art-archive']?.front).slice(0, 5)) {
          img = await cover('release', x.id); if (img) { from = '같은 음반 다른 판'; break; }
        }
      }
      if (img) return { album: r.title, date: r.date, from, img, how: `${a.name}·${how}` };
      if (seen.size >= 4) break;
    }
  }
  return { album: '(음반/표지 없음)' };
}

const { cases } = JSON.parse(fs.readFileSync(SAMPLES, 'utf8'));
const rows = []; let found = 0, scored = 0, hit = 0;
for (const [i, c] of cases.entries()) {
  const t0 = Date.now(); let r;
  try { r = await find(c); } catch (e) { r = { album: `오류: ${e.message}` }; }
  if (r.img) { found++; fs.writeFileSync(path.join(OUT, `${String(i).padStart(2, '0')}.jpg`), r.img); }
  const ok = !!r.img && c.album.some(k => norm(r.album).includes(norm(k)));
  if (c.sure) { scored++; if (ok) hit++; }
  rows.push({ 번호: i, 곡: `${c.ko} - ${c.title}`, 음반: r.album, 발매: r.date || '', 표지: r.img ? r.from : '없음', 방법: r.how || '', 판정: c.sure ? (ok ? 'O' : 'X') : '확인 필요', 초: Math.round((Date.now() - t0) / 100) / 10 });
}
console.table(rows);
console.log(`표지 찾음 ${found}/${cases.length}, 정답이 확실한 ${scored}곡 중 음반 일치 ${hit}. 표지가 맞는지는 ${OUT}의 이미지로 눈으로 확인`);
fs.writeFileSync(path.join(OUT, 'result.json'), JSON.stringify(rows, null, 1));
