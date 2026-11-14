// 판 탭 개편 (11-14): 참가자 취향 한 줄·같이 담은 곡·우리 우주에 많은 가수·최근 검색어 (화면과 떨어뜨려 테스트)
import type { Face, RoomMap } from '../roomApi'

type MapLike = Pick<RoomMap, 'planets' | 'galaxies'>

/** 우리 우주에 많은 가수: 곡 많은 순(같으면 이름 순) n명 */
export function topArtists(map: Pick<RoomMap, 'planets'> | null | undefined, n = 6): { artistId: number; name: string; count: number; face: Face | null | undefined; galaxyId: number | null }[] {
  return [...(map?.planets ?? [])]
    .map(p => ({ artistId: p.artistId, name: p.name, count: p.songs.length, face: p.face, galaxyId: p.galaxyId }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
    .slice(0, n)
}

/** 한 사람의 취향 한 줄: 가장 많이 담은 장르 구역 1개 + 많이 담은 가수 3명(곡 많은 순, 같으면 이름 순) */
export function memberTaste(map: MapLike | null | undefined, memberId: number): {
  galaxy: { id: number; name: string } | null
  artists: { artistId: number; name: string; count: number; face: Face | null | undefined }[]
} {
  const byGalaxy = new Map<number, number>()
  const artists: { artistId: number; name: string; count: number; face: Face | null | undefined }[] = []
  for (const p of map?.planets ?? []) {
    const n = p.songs.filter(s => s.memberIds.includes(memberId)).length
    if (n === 0) continue
    artists.push({ artistId: p.artistId, name: p.name, count: n, face: p.face })
    if (p.galaxyId != null) byGalaxy.set(p.galaxyId, (byGalaxy.get(p.galaxyId) ?? 0) + n)
  }
  const names = new Map((map?.galaxies ?? []).map(g => [g.id, g.name]))
  const top = [...byGalaxy].filter(([id]) => names.get(id)).sort((a, b) => b[1] - a[1] || a[0] - b[0])[0]
  return {
    galaxy: top ? { id: top[0], name: names.get(top[0])! } : null,
    artists: artists.sort((a, b) => b.count - a.count || a.name.localeCompare(b.name)).slice(0, 3),
  }
}

/** 두 사람이 같이 담은 곡 수 */
export function sharedCount(map: Pick<RoomMap, 'planets'> | null | undefined, a: number, b: number): number {
  let n = 0
  for (const p of map?.planets ?? []) for (const s of p.songs) if (s.memberIds.includes(a) && s.memberIds.includes(b)) n++
  return n
}

/** 이 곡을 같이 담은 다른 사람들 (지도 기준, 나는 뺀다) */
export function coOwners(map: Pick<RoomMap, 'planets'> | null | undefined, trackId: number, meId: number): number[] {
  for (const p of map?.planets ?? []) for (const s of p.songs) if (s.trackId === trackId) return s.memberIds.filter(id => id !== meId)
  return []
}

/** 최근 검색어: 맨 앞에 넣고 같은 말은 하나만(공백 정리·대소문자 무시), 최대 max개 */
export function pushRecent(list: string[], q: string, max = 5): string[] {
  const t = q.trim().replace(/\s+/g, ' ')
  if (!t) return list
  return [t, ...list.filter(x => x.toLowerCase() !== t.toLowerCase())].slice(0, max)
}
const RECENT_KEY = 'gyeol.recentSearch'
export function readRecent(): string[] {
  try { const v = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]'); return Array.isArray(v) ? v.filter(x => typeof x === 'string').slice(0, 5) : [] } catch { return [] }
}
export function writeRecent(list: string[]): void {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(list)) } catch { /* 기억 못 해도 검색은 된다 */ }
}
