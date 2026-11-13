import { useEffect, useRef, useState } from 'react'
import type { RoomMap } from '../roomApi'

/**
 * 연출 표시 (room-web.md 9단계 4-e·4-f·4-h·4-g): 사용자가 지도를 마지막으로 본 뒤 새로 생긴 것.
 * - tracks: 새 위성 (4-e) — `${artistId}:${trackId}`
 * - gold: 새로 겹친 노래(2명 이상이 된 순간) (4-h) — 같은 키
 * - planets: 새 행성, 또는 준비 중(4-g)이던 행성이 자리를 잡은 순간
 * - first: 빈 우주에 첫 행성 (4-f)
 */
export interface Fresh { tracks: Set<string>; gold: Set<string>; planets: Set<number>; first: boolean }
export const NO_FRESH: Fresh = { tracks: new Set(), gold: new Set(), planets: new Set(), first: false }
export const songKey = (artistId: number, trackId: number) => `${artistId}:${trackId}`

export function diffMaps(prev: RoomMap, next: RoomMap): Fresh {
  const before = new Map(prev.planets.map(p => [p.artistId, p]))
  const tracks = new Set<string>(), gold = new Set<string>(), planets = new Set<number>()
  for (const p of next.planets) {
    const old = before.get(p.artistId)
    if (!old || (old.preparing && !p.preparing)) planets.add(p.artistId)
    const oldSongs = new Map((old?.songs ?? []).map(s => [s.trackId, s]))
    for (const s of p.songs) {
      const o = oldSongs.get(s.trackId)
      if (!o) tracks.add(songKey(p.artistId, s.trackId))
      if (s.memberIds.length >= 2 && (!o || o.memberIds.length < 2)) gold.add(songKey(p.artistId, s.trackId))
    }
  }
  return { tracks, gold, planets, first: prev.planets.length === 0 && next.planets.length > 0 }
}

export const isEmptyFresh = (f: Fresh) => !f.first && f.tracks.size === 0 && f.gold.size === 0 && f.planets.size === 0

/** 연출이 끝난 뒤 표시를 지운다 (다시 그려도 반복되지 않게) */
export const FRESH_MS = 2800

/**
 * 지도 탭을 보고 있을 때(active) 받은 지도와 "마지막으로 본 지도"를 비교한다.
 * 다른 탭에 있는 동안 바뀐 것은 지도로 돌아왔을 때 연출한다. 처음 열 때는 연출하지 않는다.
 */
export function useFreshMarks(map: RoomMap | null, active: boolean): Fresh {
  const seen = useRef<RoomMap | null>(null)
  const [fresh, setFresh] = useState<Fresh>(NO_FRESH)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => () => clearTimeout(timer.current), [])
  useEffect(() => {
    if (!active || !map || map === seen.current) return
    const prev = seen.current
    seen.current = map
    if (!prev) return
    const f = diffMaps(prev, map)
    if (isEmptyFresh(f)) return
    setFresh(f)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setFresh(NO_FRESH), FRESH_MS)
  }, [map, active])
  return fresh
}
