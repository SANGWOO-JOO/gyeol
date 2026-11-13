import { describe, expect, it } from 'vitest'
import type { Planet, RoomMap } from '../roomApi'
import { diffMaps, isEmptyFresh } from './fresh'

const planet = (artistId: number, songs: [number, number[]][], preparing = false): Planet =>
  ({ artistId, name: 'a', x: 0, y: 0, galaxyId: null, preparing, songs: songs.map(([trackId, memberIds]) => ({ trackId, title: '', coverUrl: null, memberIds })) })
const map = (...planets: Planet[]): RoomMap => ({ rev: 1, placeholder: false, members: [], planets, galaxies: [], ghosts: [] })

describe('연출 표시 비교', () => {
  it('빈 우주에 첫 행성 (4-f)', () => {
    const f = diffMaps(map(), map(planet(1, [[10, [1]]])))
    expect(f.first).toBe(true)
    expect([...f.planets]).toEqual([1])
    expect([...f.tracks]).toEqual(['1:10'])
  })
  it('기존 행성에 새 위성 (4-e)', () => {
    const f = diffMaps(map(planet(1, [[10, [1]]])), map(planet(1, [[10, [1]], [11, [2]]])))
    expect(f.first).toBe(false)
    expect(f.planets.size).toBe(0)
    expect([...f.tracks]).toEqual(['1:11'])
  })
  it('혼자 담은 노래를 다른 사람이 담아 겹친 순간 (4-h)', () => {
    const f = diffMaps(map(planet(1, [[10, [1]]])), map(planet(1, [[10, [1, 2]]])))
    expect([...f.gold]).toEqual(['1:10'])
    expect(f.tracks.size).toBe(0)
  })
  it('이미 겹친 노래에 세 번째 사람이 와도 금빛 반짝임은 다시 하지 않음', () => {
    expect(diffMaps(map(planet(1, [[10, [1, 2]]])), map(planet(1, [[10, [1, 2, 3]]]))).gold.size).toBe(0)
  })
  it('준비 중이던 행성이 자리를 잡으면 새 행성처럼 (4-g → 자리)', () => {
    const f = diffMaps(map(planet(1, [[10, [1]]], true)), map(planet(1, [[10, [1]]])))
    expect([...f.planets]).toEqual([1])
  })
  it('바뀐 게 없으면 빈 표시', () => {
    const m = map(planet(1, [[10, [1]]]))
    expect(isEmptyFresh(diffMaps(m, m))).toBe(true)
  })
})
