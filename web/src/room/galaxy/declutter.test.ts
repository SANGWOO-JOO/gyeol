import { describe, expect, it } from 'vitest'
import { bucketMinScale, declutter, declutterBucket, overlapCount } from './declutter'

// 결정적인 의사 난수(테스트 데이터용)
const lcg = (seed: number) => () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648
function cluster(n: number, seed = 1) {
  const r = lcg(seed)
  const galaxies = [{ id: 1, x: 300, y: 300 }, { id: 2, x: 700, y: 300 }, { id: 3, x: 500, y: 700 }]
  const pts = Array.from({ length: n }, (_, i) => {
    const g = galaxies[i % 3]
    return { id: i + 1, x: g.x + (r() - .5) * 160, y: g.y + (r() - .5) * 160, r: 10 + r() * 14, galaxyId: g.id }
  })
  return { pts, galaxies }
}

describe('행성 겹침 풀기', () => {
  it('겹침 0, 결정적(입력 순서와 무관)', () => {
    const { pts, galaxies } = cluster(60)
    const a = declutter(pts, galaxies)
    expect(overlapCount(pts, a, 0.5)).toBe(0)
    const b = declutter([...pts].reverse(), galaxies)
    for (const p of pts) expect(b.get(p.id)).toEqual(a.get(p.id))
  })
  it('이동은 작게: 평균 이동이 반지름 정도, 겹치지 않던 행성은 그대로에 가깝다', () => {
    const { pts, galaxies } = cluster(40, 7)
    const a = declutter(pts, galaxies)
    const moves = pts.map(p => Math.hypot(a.get(p.id)!.x - p.x, a.get(p.id)!.y - p.y))
    const mean = moves.reduce((s, m) => s + m, 0) / moves.length
    expect(mean).toBeLessThan(25)
    const lonely = [{ id: 1, x: 0, y: 0, r: 5 }, { id: 2, x: 100, y: 0, r: 5 }]
    expect(declutter(lonely).get(1)).toEqual({ x: 0, y: 0 })
  })
  it('구역을 넘지 않는다: 자기 구역 중심이 늘 가장 가깝다', () => {
    const { pts, galaxies } = cluster(60, 3)
    const a = declutter(pts, galaxies)
    for (const p of pts) {
      const q = a.get(p.id)!
      const own = galaxies.find(g => g.id === p.galaxyId)!
      const d0 = Math.hypot(own.x - q.x, own.y - q.y)
      for (const g of galaxies) expect(Math.hypot(g.x - q.x, g.y - q.y)).toBeGreaterThanOrEqual(d0 - 1e-9)
    }
  })
  it('같은 자리 두 행성도 정해진 방향으로 떨어진다', () => {
    const pts = [{ id: 1, x: 50, y: 50, r: 10 }, { id: 2, x: 50, y: 50, r: 10 }]
    expect(overlapCount(pts, declutter(pts), 0.5)).toBe(0)
  })
  it('구간: 1/4옥타브, 구간 최저 배율', () => {
    expect(declutterBucket(1, 'mid')).toBe('mid:0')
    expect(declutterBucket(1.18, 'mid')).toBe('mid:0')
    expect(declutterBucket(1.2, 'mid')).toBe('mid:1')
    expect(bucketMinScale(1.5)).toBeCloseTo(Math.pow(2, 2 / 4))
  })
  it('성능: 100개 몇 ms', () => {
    // 한 번 데운 뒤 다섯 번의 가운데 값 (다른 테스트와 함께 돌 때 흔들림을 줄인다)
    const med = (n: number) => {
      const { pts, galaxies } = cluster(n, 11)
      declutter(pts, galaxies)
      const t = Array.from({ length: 5 }, () => { const t0 = performance.now(); declutter(pts, galaxies); return performance.now() - t0 }).sort((a, b) => a - b)
      return t[2]
    }
    const m40 = med(40), m100 = med(100)
    console.log('declutter 40개', m40.toFixed(2), 'ms · 100개', m100.toFixed(2), 'ms')
    expect(m100).toBeLessThan(80)
  })
})
