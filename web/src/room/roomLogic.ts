// 방 화면 규칙 (room-plan.md 9절 ⑨⑩⑪, 캔버스 4-d·4-e·4-h·4-i). 화면과 떨어뜨려 테스트한다
import type { Galaxy, Planet, RoomMap, RoomSearchItem } from './roomApi'

/** 머리의 "노래 8곡 · 가수 5명" (같은 노래를 여럿이 담아도 한 곡) */
export function mapCounts(map: RoomMap | null): { songs: number; artists: number } {
  if (!map) return { songs: 0, artists: 0 }
  return { songs: map.planets.reduce((n, p) => n + p.songs.length, 0), artists: map.planets.length }
}

/** 검색 결과 한 줄의 표시 (⑩): 이미 담음 > 친구가 담은 노래(금빛 나도 담기) > 친구와 같은 가수 > 새 노래 */
export type SearchBadge =
  | { kind: 'mine' }
  | { kind: 'sameSong'; text: string }
  | { kind: 'sameArtist'; text: string }
  | { kind: 'identify'; text: string }
  | { kind: 'none' }

export function searchBadge(r: RoomSearchItem): SearchBadge {
  if (r.mine) return { kind: 'mine' }
  // names()는 늘 "님"·"명"으로 끝나 받침이 있으므로 조사는 "이"·"과"
  if (r.sameSong.length > 0) return { kind: 'sameSong', text: `${names(r.sameSong)}이 담은 노래` }
  if (r.sameArtist.length > 0) return { kind: 'sameArtist', text: `${names(r.sameArtist)}과 같은 아티스트` }
  if (r.item.trackId == null) return { kind: 'identify', text: '처음 보는 노래 · 찾아서 담아요' }
  return { kind: 'none' }
}

/** "민지님" / "민지님·지훈님" / "민지님 외 2명" */
export function names(list: string[]): string {
  if (list.length === 0) return ''
  if (list.length <= 2) return list.map(n => `${n}님`).join('·')
  return `${list[0]}님 외 ${list.length - 1}명`
}

/** 담긴 뒤 알림 (4-e, 겹치면 4-h ⑨) */
export function addedMessage(song: { artist: string; sharedCount: number }, sameSong: string[] = []): { title: string; gold: boolean } {
  if (song.sharedCount >= 2) {
    return { title: sameSong.length > 0 ? `${names(sameSong)}과 같은 노래를 골랐어요` : `${song.sharedCount}명이 같은 노래를 골랐어요`, gold: true }
  }
  return { title: `${song.artist} 곁에 내 노래가 떴어요`, gold: false }
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
export interface GiftSummaryLike { songCount: number; nextMilestone: number; remaining: number; receivedToday: boolean; waiting: number; readyBoxId: number | null }
export interface GiftLineState { text: string; gold: boolean; badge: string | null; canOpen: boolean }

export function giftLine(g: GiftSummaryLike): GiftLineState {
  if (g.readyBoxId != null) return { text: '선물 상자가 왔어요 · 눌러서 열어요', gold: true, badge: '상자', canOpen: true }
  if (g.waiting > 0) return { text: `기다리는 상자 ${g.waiting}개 · 내일 열려요`, gold: true, badge: '상자', canOpen: false }
  if (g.receivedToday) return { text: `오늘 선물은 받았어요 · ${g.nextMilestone}곡까지 ${g.remaining}곡`, gold: false, badge: null, canOpen: false }
  const soon = g.remaining <= 3
  return { text: `${g.nextMilestone}곡 선물까지 ${g.remaining}곡`, gold: soon, badge: soon ? String(g.remaining) : null, canOpen: false }
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
