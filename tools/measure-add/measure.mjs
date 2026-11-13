// 노래 담기 경로 측정 (음악 방 ⓪, 2026-11-13)
// 곡 고르기 세션으로 3곡을 넣고 1초마다 상태를 읽어 "식별 끝"·"보강(근거·곡 설명·벡터) 끝" 시각을 잰다.
// 시험 4가지: DB 3곡 / DB 2 + Last.fm 1 / Last.fm 3 / 식별이 안 될 곡 3. 회차마다 다른 곡을 쓴다(한 번 넣으면 DB에 남아 빨라짐).
// 실행: 로컬 api(18080)·worker(LLM_MODE=bedrock, 추천 큐 제외)·fetcher·embed가 떠 있어야 한다.
//   node tools/measure-add/measure.mjs [회차=3]
// DB 곡 id는 track_vector가 있는 곡에서 고른다(psql, 로컬 compose DB).
import { execFileSync } from 'node:child_process'
import { writeFileSync, mkdirSync } from 'node:fs'

const API = process.env.API ?? 'http://localhost:18080'
const RUNS = Number(process.argv[2] ?? 3)
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function call(method, path, body) {
  const res = await fetch(API + path, { method, headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${path} ${res.status} ${text.slice(0, 200)}`)
  return text ? JSON.parse(text) : null
}

// DB에 있고 벡터까지 있는 곡
const dbSongs = execFileSync('docker', ['exec', 'gyeol-local-postgres-1', 'psql', '-U', 'gyeol', '-d', 'gyeol', '-Atc',
  `SELECT t.id, t.title, a.name FROM track t JOIN artist a ON a.id = t.artist_id JOIN track_vector v ON v.track_id = t.id ORDER BY random()`])
  .toString().trim().split('\n').map(l => { const [id, title, artist] = l.split('|'); return { trackId: Number(id), title, artist } })

// Last.fm에서 고를 곡: 검색해서 DB 결과가 하나도 없고 Last.fm 첫 결과가 있는 것만 쓴다
const LASTFM_QUERIES = [
  '혁오 TOMBOY', '잔나비 주저하는 연인들을 위해', 'AKMU 어떻게 이별까지 사랑하겠어', '검정치마 Everything', 'wave to earth bad',
  'NewJeans Ditto', 'aespa Supernova', 'BTS Spring Day', '10cm 스토커', 'DAY6 한 페이지가 될 수 있게',
  'Official髭男dism Pretender', 'YOASOBI 夜に駆ける', '米津玄師 Lemon', 'King Gnu 白日', 'Aimer 残響散歌', 'あいみょん マリーゴールド',
  'Taylor Swift Cruel Summer', 'Billie Eilish BIRDS OF A FEATHER', 'Olivia Rodrigo drivers license', 'The Weeknd Blinding Lights',
  'Phoebe Bridgers Motion Sickness', 'Lana Del Rey Summertime Sadness', 'Frank Ocean Pink + White', 'SZA Snooze',
]
async function* lastfmSongs() {
  for (const q of LASTFM_QUERIES) {
    const r = await call('GET', `/api/tracks/search?q=${encodeURIComponent(q)}&limit=5`)
    if (r.items.some(i => i.trackId)) continue
    const first = r.items[0]
    if (first) yield { title: first.title, artist: first.artist, mbid: first.mbid, query: q }
    await sleep(1100) // Last.fm 초당 1회
  }
}

// 식별이 안 될 곡: 오타 심한 제목, 커버·연주 채널, 없는 곡
const FAIL = [
  { title: '밤편지 피아노 커버 연주 1시간', artist: 'Piano Art' }, { title: 'qwzx lullaby 77', artist: 'Nobody Band Seoul' }, { title: '좋은날 3단고음 직캠 레전드', artist: '팬캠 채널' },
  { title: 'Lemon 오르골 버전 잠잘때 듣는', artist: 'Music Box Relax' }, { title: '새벽 감성 플레이리스트 2시간', artist: '플리 채널' }, { title: 'zzt9 midnight drive demo', artist: 'Unknown Garage' },
  { title: '드라이브 할때 듣는 노래 모음', artist: '감성 플리' }, { title: '봄날 기타 커버 by 동네형', artist: '동네형 기타' }, { title: 'xkcd 404 song not found', artist: 'Ghost Writer 9' },
]

const lf = lastfmSongs()
async function takeLastfm(n) { const out = []; for (let i = 0; i < n; i++) { const { value } = await lf.next(); if (!value) throw new Error('Last.fm 후보 부족'); out.push(value) } return out }
let dbIdx = 0, failIdx = 0
const takeDb = n => dbSongs.slice(dbIdx, (dbIdx += n))
const takeFail = n => FAIL.slice(failIdx, (failIdx += n))

async function measure(name, tracks) {
  const { token } = await call('POST', '/api/sessions', { entry: 'SEEDS' })
  const t0 = Date.now()
  await call('POST', `/api/sessions/${token}/seeds`, { tracks: tracks.map(({ title, artist, mbid, trackId }) => ({ title, artist, mbid: mbid ?? null, trackId: trackId ?? null })) })
  let identifiedAt = null, enrichedAt = null, last = null, noEnrichSince = null
  while (Date.now() - t0 < 10 * 60_000) {
    await sleep(1000)
    const s = await call('GET', `/api/sessions/${token}`)
    last = s
    const id = s.identify
    const idDone = !id || id.done + id.failed >= id.target
    if (idDone && identifiedAt == null) identifiedAt = Date.now()
    const en = s.enrich
    if (en && en.target > 0 && en.done + en.failed >= en.target) { enrichedAt = Date.now(); break }
    if (idDone && !en) { noEnrichSince ??= Date.now(); if (Date.now() - noEnrichSince > 20_000) break } else noEnrichSince = null
  }
  const found = last?.seeds?.filter(x => x.releaseTitle || x.coverUrl).length
  const r = {
    case: name, token,
    tracks: tracks.map(t => `${t.artist} - ${t.title}${t.trackId ? ' (DB)' : ''}`),
    identify: last?.identify ?? null, enrich: last?.enrich ?? null,
    identifySec: identifiedAt ? (identifiedAt - t0) / 1000 : null,
    readySec: enrichedAt ? (enrichedAt - t0) / 1000 : null,
    seedsWithRelease: found,
  }
  console.log(JSON.stringify({ case: r.case, identify: r.identify, enrich: r.enrich, identifySec: r.identifySec, readySec: r.readySec }))
  return r
}

const results = []
for (let run = 1; run <= RUNS; run++) {
  results.push(await measure(`①DB3 #${run}`, takeDb(3)))
  results.push(await measure(`②DB2+LF1 #${run}`, [...takeDb(2), ...(await takeLastfm(1))]))
  results.push(await measure(`③LF3 #${run}`, await takeLastfm(3)))
  results.push(await measure(`④FAIL3 #${run}`, takeFail(3)))
}
mkdirSync('tools/results', { recursive: true })
writeFileSync('tools/results/measure-add.json', JSON.stringify(results, null, 2))
console.log('저장: tools/results/measure-add.json')
