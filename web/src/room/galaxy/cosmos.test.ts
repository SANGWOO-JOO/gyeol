import { describe, expect, it } from 'vitest'
import { cometPath, decorDelay, newSongs, parallaxPositions, songKeys, starTile, STAR_LAYERS, twinkles } from './cosmos'

describe('지도 배경 계산', () => {
  it('별 타일: 같은 씨앗이면 같은 별, 겹마다 개수·크기 범위', () => {
    expect(starTile(5, 0)).toEqual(starTile(5, 0))
    expect(starTile(5, 0)).not.toEqual(starTile(6, 0))
    STAR_LAYERS.forEach((L, i) => {
      const t = starTile(1, i)
      expect(t.stars).toHaveLength(L.count)
      for (const s of t.stars) { expect(s.r).toBeGreaterThanOrEqual(L.r[0]); expect(s.r).toBeLessThanOrEqual(L.r[1]); expect(s.x).toBeLessThan(L.tile) }
    })
  })
  it('시차: 먼 겹일수록 덜 움직인다', () => {
    const pos = parallaxPositions(100, -40).split(', ').map(p => p.split(' ').map(parseFloat))
    expect(pos[0][0]).toBeLessThan(pos[1][0]); expect(pos[1][0]).toBeLessThan(pos[2][0])
    expect(pos[2][1]).toBeCloseTo(-40 * STAR_LAYERS[2].factor)
  })
  it('반짝임: 화면 안 자리, 결정적', () => {
    const t = twinkles(3)
    expect(t).toEqual(twinkles(3))
    for (const s of t) { expect(s.left).toBeGreaterThan(0); expect(s.left).toBeLessThan(100) }
  })
  it('혜성: 화면 밖에서 출발해 목표에 닿는다', () => {
    const view = { width: 1000, height: 800 }
    for (const target of [{ x: 800, y: 200 }, { x: 100, y: 700 }, { x: 500, y: 400 }]) {
      const p = cometPath(target, view, 2)
      expect(p.ex).toBe(target.x); expect(p.ey).toBe(target.y)
      const outside = p.sx < 0 || p.sx > view.width || p.sy < 0 || p.sy > view.height
      expect(outside).toBe(true)
    }
  })
  it('새 곡: 첫 지도는 없음, 새 곡만, 상한', () => {
    const planets = [{ artistId: 1, songs: [{ trackId: 1, memberIds: [7] }, { trackId: 2, memberIds: [7, 8] }] }, { artistId: 2, songs: [{ trackId: 3, memberIds: [9] }] }]
    expect(newSongs(null, planets)).toEqual([])
    const prev = new Set(['1:1'])
    expect(newSongs(prev, planets)).toEqual([{ artistId: 1, trackId: 2, memberId: 8 }, { artistId: 2, trackId: 3, memberId: 9 }])
    expect(newSongs(new Set(), planets, 1)).toEqual([{ artistId: 2, trackId: 3, memberId: 9 }])
    expect(songKeys(planets).size).toBe(3)
  })
  it('장식 혜성 간격 30~60초', () => {
    for (let i = 0; i < 20; i++) { const d = decorDelay(11, i); expect(d).toBeGreaterThanOrEqual(30000); expect(d).toBeLessThan(60000) }
  })
})
