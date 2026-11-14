// 내 노래 장르별·아티스트별 (11-14). 응답 모양이 확정되면 MySongGroup만 맞춘다.
import type { Face } from '../roomApi'

export type MineSort = 'recent' | 'genre' | 'artist'
export const MINE_SORTS: MineSort[] = ['recent', 'genre', 'artist']
const KEY = 'gyeol.mineSort'

export function readMineSort(): MineSort {
  try { const v = localStorage.getItem(KEY); return v === 'genre' || v === 'artist' ? v : 'recent' } catch { return 'recent' }
}
export function writeMineSort(v: MineSort): void {
  try { localStorage.setItem(KEY, v) } catch { /* 기억 못 해도 화면은 그대로 */ }
}

/** 곡에 붙는 묶음 (11-14 API): key(genre:100 · artist:1810 · genre:none), kind GENRE·ARTIST, name, count(쪽과 무관한 전체 곡 수), galaxyId, artistId·displayName·face */
export interface MySongGroup {
  key?: string | null
  kind?: string | null
  galaxyId?: number | null
  name?: string | null
  artistId?: number | null
  displayName?: string | null
  face?: Face | null
  count?: number | null
}

export interface GroupHead { key: string; title: string; count: number | null; galaxyId: number | null; artistId: number | null; face: Face | null }
export interface SongGroup<T> { head: GroupHead; items: T[] }

/** 묶음 키: 서버 key가 있으면 그것, 없으면 정렬에 맞는 id. 분류가 없으면 'none'("분류 중") */
export function groupHead(sort: MineSort, g: MySongGroup | null | undefined): GroupHead {
  const id = sort === 'genre' ? g?.galaxyId : g?.artistId
  const key = g?.key ?? (id != null ? `${sort}:${id}` : `${sort}:none`)
  const title = (sort === 'artist' ? g?.displayName ?? g?.name : g?.name) ?? '분류 중'
  return { key, title, count: g?.count ?? null, galaxyId: g?.galaxyId ?? null, artistId: g?.artistId ?? null, face: g?.face ?? null }
}

/**
 * 받은 곡(여러 쪽을 이어 붙인 목록)을 묶음으로: 같은 키는 처음 나온 묶음 아래로 모은다(쪽 경계에서 잘린 묶음도 하나로).
 * 묶음 순서는 처음 나온 순서(서버가 곡 많은 순·분류 중 맨 뒤로 준다)
 */
export function groupSongs<T extends { group?: MySongGroup | null }>(sort: MineSort, items: T[]): SongGroup<T>[] {
  const out: SongGroup<T>[] = []
  const at = new Map<string, SongGroup<T>>()
  for (const it of items) {
    const head = groupHead(sort, it.group)
    let g = at.get(head.key)
    if (!g) { g = { head, items: [] }; at.set(head.key, g); out.push(g) }
    g.items.push(it)
  }
  return out
}
