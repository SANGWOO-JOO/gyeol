// 방 화면 규칙 (room-plan.md 9절 ⑨⑩⑪, 캔버스 4-d·4-e·4-h·4-i). 화면과 떨어뜨려 테스트한다
import type { ArtistSongItem, Galaxy, Planet, RoomMap, RoomSearchItem, SearchArtist, TrackItem } from './roomApi'

/** 머리의 "노래 8곡 · 가수 5명" (같은 노래를 여럿이 담아도 한 곡) */
export function mapCounts(map: RoomMap | null): { songs: number; artists: number } {
  if (!map) return { songs: 0, artists: 0 }
  return { songs: map.planets.reduce((n, p) => n + p.songs.length, 0), artists: map.planets.length }
}

/**
 * 검색 결과 한 줄의 표시 (⑩): 이미 담음 > 친구가 담은 노래(금빛 나도 담기) > 친구와 같은 가수 > 표시 없음.
 * DB에 없는 곡도 따로 표시하지 않는다(I-29): 담은 뒤 찾는 중은 지도 점선 고리·찾는 중 띠, 실패하면 4-i 안내.
 */
export type SearchBadge =
  | { kind: 'mine' }
  | { kind: 'sameSong'; text: string }
  | { kind: 'sameArtist'; text: string }
  | { kind: 'none' }

export function searchBadge(r: RoomSearchItem): SearchBadge {
  if (r.mine) return { kind: 'mine' }
  // names()는 늘 "님"·"명"으로 끝나 받침이 있으므로 조사는 "이"·"과"
  if (r.sameSong.length > 0) return { kind: 'sameSong', text: `${names(r.sameSong)}이 담은 노래` }
  if (r.sameArtist.length > 0) return { kind: 'sameArtist', text: `${names(r.sameArtist)}과 같은 아티스트` }
  return { kind: 'none' }
}

/** "민지님" / "민지님·지훈님" / "민지님 외 2명" */
export function names(list: string[]): string {
  if (list.length === 0) return ''
  if (list.length <= 2) return list.map(n => `${n}님`).join('·')
  return `${list[0]}님 외 ${list.length - 1}명`
}

/** 담긴 뒤 알림 (4-e, 겹치면 4-h ⑨) */
export function addedMessage(song: { artist: string; sharedCount: number; title?: string }, sameSong: string[] = [], newArtist = false): { title: string; gold: boolean; sub?: string } {
  if (song.sharedCount >= 2) {
    return { title: sameSong.length > 0 ? `${names(sameSong)}과 같은 노래를 골랐어요` : `${song.sharedCount}명이 같은 노래를 골랐어요`, gold: true }
  }
  // I-52 (캔버스 4-g 문구): 공용 지도에 없던 가수. 진행 카드 없이 알림 문구만, 점선 고리 행성이 준비 중을 보여 준다
  if (newArtist) return { title: '처음 보는 아티스트예요', gold: false, sub: `${song.artist}의 자리를 우주에 만드는 중이에요${song.title ? ` · ${song.title}` : ''}` }
  return { title: `${song.artist} 곁에 내 노래가 떴어요`, gold: false }
}

/**
 * I-52 처음 보는 가수인지: 담은 직후 받은 지도에서 이 곡이 들어 있는 행성이 준비 중(preparing = 공용 지도에 아직 자리가 없어
 * 자리를 만드는 중)이면 true. 담기 응답에는 가수 id·준비 상태가 없어서 지도로 판단한다. 지도를 못 받았으면 false(일반 알림)
 */
export function isNewArtist(map: Pick<RoomMap, 'planets'> | null | undefined, trackId: number): boolean {
  const p = map?.planets.find(x => x.songs.some(s => s.trackId === trackId))
  return !!p?.preparing
}

/** 식별 실패 (4-i ⑪): 담지 않는다. 화면은 안내 한 줄 + 다른 이름으로 찾아보기·닫기 */
export function failText(reason: string | null): string {
  switch (reason) {
    case 'REJECTED': return '노래가 아닌 영상이나 메들리라서 담지 못했어요'
    case 'ERROR': return '찾는 중에 문제가 생겼어요. 잠시 뒤 다시 담아 주세요'
    case 'MEMBER_REMOVED': return '이 우주에서 나가서 담지 못했어요'
    default: return '이 노래를 찾지 못했어요. 영어 제목이나 원래 제목으로 찾으면 잘 나와요'
  }
}

/** 담기 오류 문구 */
export function addErrorText(code: string, fallback: string): string {
  switch (code) {
    case 'ALREADY_ADDED': return '이미 담은 노래예요'
    case 'DAILY_LIMIT': return fallback // "오늘은 100곡까지 …"는 서버 문구가 한도 숫자를 담고 있다
    case 'TOO_MANY_PENDING': return '찾는 중인 노래가 많아요. 잠시 뒤 다시 담아 주세요'
    default: return fallback
  }
}

/** 임시 지도 판: 구역(은하)별로 가수를 묶는다. 구역 없는 가수는 "아직 자리를 찾는 중" 묶음 */
export interface GalaxyGroup { id: number | null; name: string; planets: Planet[] }

export function groupByGalaxy(map: RoomMap): GalaxyGroup[] {
  const byId = new Map<number, Galaxy>(map.galaxies.map(g => [g.id, g]))
  const groups = new Map<number | null, Planet[]>()
  for (const p of map.planets) {
    const key = p.galaxyId != null && byId.has(p.galaxyId) ? p.galaxyId : null
    groups.set(key, [...(groups.get(key) ?? []), p])
  }
  const songCount = (ps: Planet[]) => ps.reduce((n, p) => n + p.songs.length, 0)
  return [...groups.entries()]
    .map(([id, planets]) => ({
      id,
      name: id == null ? '자리를 찾는 중' : byId.get(id)?.name ?? '이름 짓는 중인 구역',
      planets: [...planets].sort((a, b) => b.songs.length - a.songs.length || a.name.localeCompare(b.name)),
    }))
    .sort((a, b) => (a.id == null ? 1 : 0) - (b.id == null ? 1 : 0) || songCount(b.planets) - songCount(a.planets))
}

/** 트랙 → 그 노래가 있는 구역 이름 (내 노래 줄의 "가수 · 구역") */
export function galaxyOfTrack(map: RoomMap | null): Map<number, string> {
  const out = new Map<number, string>()
  if (!map) return out
  const names = new Map(map.galaxies.map(g => [g.id, g.name]))
  for (const p of map.planets) {
    const name = p.galaxyId != null ? names.get(p.galaxyId) : null
    if (name) p.songs.forEach(s => out.set(s.trackId, name))
  }
  return out
}

/** 가수 판의 노래 한 줄: 같이 담음(금빛) · 내 노래(✕) · 친구 노래 */
export function songOwners(memberIds: number[], meId: number, nameOf: (id: number) => string | undefined): { label: string; mine: boolean; gold: boolean } {
  const mine = memberIds.includes(meId)
  const others = memberIds.filter(id => id !== meId).map(nameOf).filter((n): n is string => !!n)
  if (memberIds.length >= 2) return { label: `${[...(mine ? ['나'] : []), ...others].join(' · ')} 같이 담음`, mine, gold: true }
  return { label: mine ? '나' : others[0] ?? '', mine, gold: false }
}

/** 근거 출처 표시 이름 */
export function sourceName(source: string): string {
  switch (source) {
    case 'WIKIPEDIA': return '위키백과'
    case 'LASTFM': return 'Last.fm'
    case 'MUSICBRAINZ': return 'MusicBrainz'
    default: return source
  }
}

/**
 * 선물 표시 (room-plan ⑯ 남은 곡 표시, 캔버스 4-l). 내 노래 탭 맨 위 줄은 늘 있고, 탭 배지는
 * 곧 받거나(3곡 이하) 상자가 있을 때만 뜬다. 오늘 이미 받았으면 배지 없음.
 */
export interface GiftSummaryLike {
  songCount: number; nextMilestone: number; remaining: number; receivedToday: boolean; waiting: number; readyBoxId: number | null
  /** ⑯ 변경(11-14): 서비스 전체 오늘 남은 새 그림 장수. API가 아직 안 주면 없음 → 표시 생략 */
  imagesLeftToday?: number | null
}
export interface GiftLineState { text: string; gold: boolean; badge: string | null; canOpen: boolean }

export function giftLine(g: GiftSummaryLike): GiftLineState {
  if (g.readyBoxId != null) {
    const left = g.imagesLeftToday
    const text = left == null ? '선물 상자가 왔어요 · 눌러서 열어요'
      : left > 0 ? `선물 상자가 왔어요 · 오늘 새 그림 ${left}장 남음`
      : '선물 상자가 왔어요 · 오늘 새 그림은 다 나갔어요'
    return { text, gold: true, badge: '상자', canOpen: true }
  }
  if (g.waiting > 0) return { text: `기다리는 상자 ${g.waiting}개 · 내일 열려요`, gold: true, badge: '상자', canOpen: false }
  if (g.receivedToday) return { text: `오늘 선물은 받았어요 · ${g.nextMilestone}곡까지 ${g.remaining}곡`, gold: false, badge: null, canOpen: false }
  const soon = g.remaining <= 3
  return { text: `${g.nextMilestone}곡 선물까지 ${g.remaining}곡`, gold: soon, badge: soon ? String(g.remaining) : null, canOpen: false }
}

/**
 * 코드 맞추기 결과 표시 (room-web.md 8-b, 11-13 결정 "라"). 곡이 적으면 점수가 낮게 치우쳐서(측정: 5곡은 약 35점 낮음)
 * 둘 중 적은 쪽이 10곡 미만(LOW)이면 % 대신 겹친 것만, 10곡부터 %. 보통(MEDIUM)은 % + "20곡이면 더 정확" 안내.
 */
export interface CodeMatchLike { score: number; confidence: string; minSongs: number; sharedArtistCount: number; sharedGalaxyCount: number; sharedSongs: number }
export interface CodeMatchDisplay { showPercent: boolean; headline: string; note: string | null; shared: string[] }

export function codeMatchDisplay(m: CodeMatchLike): CodeMatchDisplay {
  const shared = [
    `같은 아티스트 ${m.sharedArtistCount}명`,
    `같은 구역 ${m.sharedGalaxyCount}곳`,
    `같이 담은 노래 ${m.sharedSongs}곡`,
  ]
  if (m.confidence === 'LOW') {
    const found = m.sharedArtistCount + m.sharedGalaxyCount + m.sharedSongs > 0
    return {
      showPercent: false,
      headline: found ? '벌써 겹치는 게 있어요' : '아직 겹치는 게 없어요',
      note: '각자 10곡을 넘기면 코드 점수가 나와요',
      shared,
    }
  }
  return {
    showPercent: true,
    headline: `코드가 ${m.score}% 맞아요`,
    note: m.confidence === 'MEDIUM' ? '각자 20곡을 넘기면 더 정확해져요' : null,
    shared,
  }
}

/** 4-k 새 캐릭터 문구: "장르 융합 보컬 13곡 · 청량 팝 걸그룹 11곡 …" (비면 null → 일반 문구) */
export function genreMixText(mix: { galaxy: string; songs: number }[] | undefined): string | null {
  const parts = (mix ?? []).filter(x => x.galaxy && x.songs > 0).slice(0, 4).map(x => `${x.galaxy} ${x.songs}곡`)
  return parts.length > 0 ? parts.join(' · ') : null
}

/** 놀이터 고르기: 5곡 미만이면 "N곡 더 담으면 열려요" */
export function unlockText(songCount: number, min = 5): string | null {
  return songCount >= min ? null : `${min - songCount}곡 더 담으면 열려요`
}

/** 행성에 노래를 담은 사람들 (위성 색), 같이 담은 노래 수 (금빛) */
export function planetPeople(p: Planet): { memberIds: number[]; shared: number } {
  const ids = new Set<number>()
  let shared = 0
  for (const s of p.songs) {
    s.memberIds.forEach(id => ids.add(id))
    if (s.memberIds.length >= 2) shared++
  }
  return { memberIds: [...ids], shared }
}

/**
 * ⑯ 변경(11-14) 새 그림 하루 10장 선착순: 상자 판(4-j)에 열기 전에 남은 장수를 알린다.
 * 둘 중 하나라도 없으면(API 준비 전) null → 표시하지 않고 지금 화면 그대로.
 */
export interface ImageQuota { text: string; soldOut: boolean }
export function imageQuota(left?: number | null, limit?: number | null): ImageQuota | null {
  if (left == null || limit == null) return null
  const n = Math.max(0, left)
  return { text: `오늘 남은 새 그림 ${n}/${limit}장 · 선착순`, soldOut: n === 0 }
}

/** 4-k 결과 문구: 기본 캐릭터를 준 이유(fallbackReason)에 따라 사실대로 */
export function giftResultText(isFallback: boolean, reason?: string | null): { title: string; text: string | null } {
  if (!isFallback) return { title: '', text: null }
  if (reason === 'DAILY_LIMIT') return { title: '기본 캐릭터를 보냈어요', text: '방금 오늘 몫이 다 떨어져 기본 캐릭터를 보냈어요.' }
  return { title: '기본 캐릭터를 보냈어요', text: '오늘은 새 그림을 만들 수 없어서 미리 준비한 캐릭터를 드려요.' }
}

// ── I-30 가수 화면 (docs/todo/artist-search.md) ──

/** 검색 결과·가수 화면에서 한 곡을 구분하는 키 (DB 곡은 trackId, 아니면 제목|가수) */
export const trackKey = (t: TrackItem) => (t.trackId != null ? `t${t.trackId}` : `q${t.title}|${t.artist}`.toLowerCase())

/** 가수 카드·가수 머리의 둘째 줄. 서버가 곡 수를 안 주면 지도 여부만, 그것도 없으면 빈 줄 */
export function artistCardSub(a: SearchArtist): string {
  if (a.songCountInRoom != null) return a.songCountInRoom > 0 ? `우리 우주에 ${a.songCountInRoom}곡` : '아직 우리 우주에 담은 노래가 없어요'
  return a.onMap ? '우리 지도에 있는 가수예요' : ''
}

/** 가수 화면 머리: 카드(곡 수·지도 여부)에 서버 응답(이름·별칭)을 덮되, 응답에 없는 값은 카드 것을 남긴다 */
export function mergeArtist(base: SearchArtist, next: SearchArtist | null | undefined): SearchArtist {
  if (!next) return base
  const out: SearchArtist = { ...base }
  for (const [k, v] of Object.entries(next) as [keyof SearchArtist, never][]) if (v != null) out[k] = v
  return out
}

/** 가수 화면 오류 문구 (② 503 Last.fm 첫 쪽 실패, 404 없는 가수, 501 최신순 준비 중) */
export function artistSongsErrorText(status: number, code: string, fallback: string): string {
  if (status === 503 || code === 'ARTIST_SONGS_UNAVAILABLE') return '지금은 이 가수의 노래 목록을 가져오지 못했어요. 잠시 뒤 다시 시도해 주세요'
  if (status === 404 || code === 'ARTIST_NOT_FOUND') return '이 가수를 찾지 못했어요'
  if (status === 501 || code === 'LATEST_NOT_READY') return '최신순은 준비 중이에요. 인기순에서 찾아보세요'
  if (status === 0) return fallback
  return '노래를 불러오지 못했어요'
}

/** 인기순은 100곡(20곡 × 5페이지)까지 (결정 A6) */
export const ARTIST_MAX_PAGES = 5
export function canLoadMore(p: { hasMore: boolean; page: number }): boolean {
  return p.hasMore && p.page < ARTIST_MAX_PAGES
}

/** 가수 화면 끝 문구 (I-42 B): 5쪽(100곡) 상한에 닿았으면 "여기까지", 실제로 곡이 끝났으면 "다 봤어요" */
export function artistEndText(p: { hasMore: boolean; page: number }, count: number): string {
  const capped = p.page >= ARTIST_MAX_PAGES && (p.hasMore || count >= ARTIST_MAX_PAGES * 20)
  return capped ? '여기까지 보여 드려요 · 다른 노래는 검색으로 찾아보세요' : '이 가수의 노래를 다 봤어요'
}

/** 이어 불러온 페이지를 붙인다. 페이지 사이에 같은 곡이 다시 오면 앞의 것을 남긴다 */
export function appendSongs(prev: ArtistSongItem[], next: ArtistSongItem[]): ArtistSongItem[] {
  const seen = new Set(prev.map(x => trackKey(x.item)))
  return [...prev, ...next.filter(x => { const k = trackKey(x.item); if (seen.has(k)) return false; seen.add(k); return true })]
}

/** 가수 화면 곡을 방 검색과 같은 모양으로 (담기·금빛 표시를 그대로 쓰려고) */
export const asSearchItem = (x: ArtistSongItem): RoomSearchItem => ({ item: x.item, mine: x.mine, sameSong: x.sameSong, sameArtist: [] })

/** 가수 화면: 내가 담은 곡을 친구도 담았으면 금빛 "민지님과 같이 담음" (names()는 받침이 있어 "과") */
export function sharedWithText(sameSong: string[]): string | null {
  return sameSong.length > 0 ? `${names(sameSong)}과 같이 담음` : null
}

/** "2021-03-05" → "2021.03.05", "2021-03" → "2021.03", "2021" → "2021" */
export function releaseText(d: string | null | undefined): string | null {
  const m = d?.match(/^(\d{4})(?:-(\d{2}))?(?:-(\d{2}))?/)
  return m ? [m[1], m[2], m[3]].filter(Boolean).join('.') : null
}

/** 최신순 묶음 (③): 서버 순서(최신부터) 그대로 연도별로 나누고, 날짜 없는 곡은 맨 뒤 "발매일 모름" */
export interface ReleaseGroup { label: string; unknown: boolean; items: ArtistSongItem[] }
export function groupByRelease(items: ArtistSongItem[]): ReleaseGroup[] {
  const groups: ReleaseGroup[] = []
  const unknown: ArtistSongItem[] = []
  for (const x of items) {
    const y = x.releaseDate?.match(/^\d{4}/)?.[0]
    if (!y) { unknown.push(x); continue }
    const last = groups[groups.length - 1]
    if (last && last.label === y) last.items.push(x)
    else groups.push({ label: y, unknown: false, items: [x] })
  }
  if (unknown.length > 0) groups.push({ label: '발매일 모름', unknown: true, items: unknown })
  return groups
}

/** 최신순 수집 중 다시 읽기 간격: 실제 수집은 보통 2~4초(11-14 실측)라 처음엔 빨리, 오래 걸리면 4초까지 늘린다 */
export const scanPollMs = (tick: number) => (tick === 0 ? 1200 : tick < 3 ? 2000 : 4000)

/** 최신순 상단 안내 (③) */
export function scanNotice(scan: string | null | undefined): { title: string; sub: string; busy: boolean } | null {
  if (scan === 'QUEUED' || scan === 'RUNNING') return { title: '발매일을 모으는 중이에요', sub: '처음 한 번만 걸려요 · 모은 노래부터 보여 드려요', busy: true }
  if (scan === 'FAILED') return { title: '발매일을 다 모으지 못했어요', sub: '모은 노래만 보여요 · 인기순에서 더 찾아보세요', busy: false }
  return null
}

/** 검색 목록 끝 "가수 노래 전체 보기" 버튼: 가수 카드가 있고 결과가 1곡 이상, 불러오는 중이 아닐 때 */
export function showArtistMore(artist: unknown, items: unknown[] | null | undefined, loading: boolean): boolean {
  return !!artist && !!items && items.length > 0 && !loading
}

/** 검색 더보기: 다음 쪽을 이어 붙이되 이미 보인 곡(trackKey: trackId, 없으면 제목|가수)은 빼고, 다음 쪽 안의 중복도 뺀다 */
export function appendSearch<T extends { item: Parameters<typeof trackKey>[0] }>(prev: T[], next: T[]): T[] {
  const seen = new Set(prev.map(x => trackKey(x.item)))
  return [...prev, ...next.filter(x => { const k = trackKey(x.item); if (seen.has(k)) return false; seen.add(k); return true })]
}

/** 검색 가수 카드 얼굴 주소: 응답의 face(PHOTO 사진 · COVER 방 곡 표지)를 그대로, NONE이거나 없으면 null(이니셜) */
export function artistFaceUrl(a: { face?: { kind: string; url: string | null } | null }): string | null {
  const f = a.face
  return f && (f.kind === 'PHOTO' || f.kind === 'COVER') && f.url ? f.url : null
}

/** 검색 가수 표시 이름: DB 이름(displayName, 지도와 같음) → MusicBrainz 이름 */
export const artistDisplayName = (a: { name: string; displayName?: string | null }) => a.displayName || a.name

/**
 * 검색을 보낼지 (11-14): 두 글자부터, 한 글자는 한글 완성 글자·한자·히라가나·가타카나일 때만(예: 비, 風).
 * 라틴·숫자 한 글자와 조합 중 자모(ㅂ)는 보내지 않는다. 서버도 같은 규칙
 */
export function searchable(q: string): boolean {
  const t = q.trim()
  const chars = Array.from(t)
  if (chars.length >= 2) return true
  if (chars.length === 0) return false
  return /[\uAC00-\uD7A3\u3040-\u309F\u30A0-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]/.test(chars[0])
}
/** 검색 대기 시간(ms): 한 글자는 조금 길게(한글 조합 ㅂ→비 동안 요청이 늘지 않게) */
export const searchDelay = (q: string) => (Array.from(q.trim()).length === 1 ? 400 : 300)
