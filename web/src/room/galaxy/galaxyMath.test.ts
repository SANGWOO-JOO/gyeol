import { describe, expect, it } from 'vitest'
import { GROW_MAX, orbitSpin, pushOut, zoomAnchor, zoomGrow, clampScale, screenSpacing, fitView, MAX_SCALE, MIN_SCALE, pan, planetRadius, screenPlanetRadius, stars, visibleLabels, zoomAt, zoomLevel } from './galaxyMath'

const size = { width: 390, height: 600 }

describe('fitView', () => {
  it('모든 행성이 화면 안에 들어온다', () => {
    const pts = [{ x: 100, y: 200, r: 20 }, { x: 800, y: 700, r: 30 }]
    const v = fitView(pts, size)
    for (const p of pts) {
      const sx = p.x * v.scale + v.tx, sy = p.y * v.scale + v.ty
      expect(sx - p.r * v.scale).toBeGreaterThanOrEqual(0)
      expect(sx + p.r * v.scale).toBeLessThanOrEqual(size.width)
      expect(sy).toBeGreaterThanOrEqual(0)
      expect(sy).toBeLessThanOrEqual(size.height)
    }
  })
  it('행성 하나는 가운데에, 지나치게 확대하지 않는다', () => {
    const v = fitView([{ x: 500, y: 500, r: 16 }], size)
    expect(500 * v.scale + v.tx).toBeCloseTo(size.width / 2)
    expect(500 * v.scale + v.ty).toBeCloseTo(size.height / 2)
    expect(v.scale).toBeLessThanOrEqual(2.4)
  })
  it('빈 지도는 세계 전체', () => {
    const v = fitView([], size)
    expect(v.scale).toBeGreaterThanOrEqual(MIN_SCALE)
  })
})

describe('확대·이동', () => {
  it('확대해도 손가락 아래 점은 그대로', () => {
    const v = { scale: 1, tx: 10, ty: 20 }
    const z = zoomAt(v, 200, 300, 2)
    const wx = (200 - v.tx) / v.scale, wy = (300 - v.ty) / v.scale
    expect(wx * z.scale + z.tx).toBeCloseTo(200)
    expect(wy * z.scale + z.ty).toBeCloseTo(300)
  })
  it('배율은 범위 안으로', () => {
    expect(clampScale(100)).toBe(MAX_SCALE)
    expect(clampScale(0.001)).toBe(MIN_SCALE)
    expect(zoomAt({ scale: MAX_SCALE, tx: 0, ty: 0 }, 0, 0, 2).scale).toBe(MAX_SCALE)
  })
  it('이동', () => {
    expect(pan({ scale: 1, tx: 1, ty: 2 }, 5, -3)).toEqual({ scale: 1, tx: 6, ty: -1 })
  })
  it('확대 단계', () => {
    expect(zoomLevel(0.15)).toBe('far')
    expect(zoomLevel(0.3)).toBe('mid')
    expect(zoomLevel(1)).toBe('mid')
    expect(zoomLevel(2)).toBe('near')
  })
})

describe('이름 겹침', () => {
  it('겹치면 노래가 많은 행성 이름만 남긴다', () => {
    const items = [
      { id: 1, sx: 100, sy: 100, r: 10, w: 60, weight: 1 },
      { id: 2, sx: 110, sy: 102, r: 10, w: 60, weight: 5 },
      { id: 3, sx: 300, sy: 100, r: 10, w: 60, weight: 1 },
    ]
    expect([...visibleLabels(items)].sort()).toEqual([2, 3])
  })
  it('화면 반지름은 확대할수록 조금 커지고 노래가 많을수록 크다', () => {
    expect(screenPlanetRadius(1, 2)).toBeGreaterThan(screenPlanetRadius(1, 0.5))
    expect(screenPlanetRadius(9, 1)).toBeGreaterThan(screenPlanetRadius(1, 1))
  })
})

describe('그림 값', () => {
  it('노래가 많을수록 큰 행성', () => {
    expect(planetRadius(9)).toBeGreaterThan(planetRadius(1))
  })
  it('별 무늬는 방마다 같다', () => {
    expect(stars('abc', 5)).toEqual(stars('abc', 5))
    expect(stars('abc', 5)).not.toEqual(stars('xyz', 5))
  })
})

describe('장애물', () => {
  it('장애물과 겹치는 이름은 숨긴다', () => {
    const items = [{ id: 1, sx: 100, sy: 100, r: 0, w: 40, weight: 1 }, { id: 2, sx: 300, sy: 100, r: 0, w: 40, weight: 1 }]
    expect([...visibleLabels(items, 14, [{ x0: 90, x1: 110, y0: 95, y1: 110 }])]).toEqual([2])
  })
})

describe('최소 크기와 맞춤 여백', () => {
  it('행성 지름은 어느 배율에서도 14px 이상', () => {
    expect(screenPlanetRadius(1, 0.15) * 2).toBeGreaterThanOrEqual(14)
  })
  it('맞춤은 행성 범위에 12% 여백만 둔다 (가로가 좁으면 가로를 꽉 채움)', () => {
    const v = fitView([{ x: 100, y: 100 }, { x: 830, y: 800 }], { width: 375, height: 568 })
    expect(730 * v.scale).toBeCloseTo(375 * 0.76)
  })
})

describe('빽빽할 때 확대 단계', () => {
  it('행성이 빽빽하면 배율이 커도 멀리 보기(숫자만)', () => {
    expect(zoomLevel(0.5, 30)).toBe('far')
    expect(zoomLevel(2, 60)).toBe('mid')
    expect(zoomLevel(0.5, 100)).toBe('mid')
  })
  it('평균 간격은 화면 안 행성만 센다', () => {
    const v = { scale: 1, tx: 0, ty: 0 }
    expect(screenSpacing([{ x: 10, y: 10 }, { x: 5000, y: 10 }], v, { width: 100, height: 100 })).toBeCloseTo(100)
    expect(screenSpacing([], v, { width: 100, height: 100 })).toBe(Infinity)
  })
})

describe('I-37 확대 체감', () => {
  it('처음 맞춘 배율보다 확대하면 √로 커지고 상한에서 멈춘다, 축소 쪽은 1', () => {
    expect(zoomGrow(2.4, 2.4)).toBe(1)
    expect(zoomGrow(4.8, 2.4)).toBeCloseTo(Math.SQRT2)
    expect(zoomGrow(6, 0.5)).toBe(GROW_MAX)
    expect(zoomGrow(0.3, 0.6)).toBe(1)
    expect(zoomGrow(1, 0)).toBe(1)
  })
})

describe('I-38 공전', () => {
  it('한 바퀴 30~60초, 같은 행성·궤도면 늘 같은 값, 궤도마다 다르다', () => {
    const all = Array.from({ length: 300 }, (_, i) => orbitSpin(i * 7 + 1, i % 4))
    expect(all.every(x => x.seconds >= 30 && x.seconds <= 60)).toBe(true)
    expect(orbitSpin(42, 1)).toEqual(orbitSpin(42, 1))
    expect(new Set([0, 1, 2, 3].map(i => orbitSpin(42, i).seconds)).size).toBeGreaterThan(1)
    const rev = all.filter(x => x.reverse).length
    expect(rev).toBeGreaterThan(30)
    expect(rev).toBeLessThan(270)
  })
})

describe('I-43 +/− 기준점', () => {
  const size = { width: 400, height: 300 }
  it('화면 안 행성 중 가운데에 가장 가까운 것', () => {
    expect(zoomAnchor([{ x: 20, y: 20 }, { x: 210, y: 140 }, { x: 390, y: 290 }], size)).toEqual({ x: 210, y: 140 })
  })
  it('화면 밖 행성은 빼고, 하나도 없으면 화면 가운데', () => {
    expect(zoomAnchor([{ x: -50, y: 150 }, { x: 380, y: 10 }], size)).toEqual({ x: 380, y: 10 })
    expect(zoomAnchor([{ x: -50, y: 150 }, { x: 900, y: 10 }], size)).toEqual({ x: 200, y: 150 })
    expect(zoomAnchor([], size)).toEqual({ x: 200, y: 150 })
  })
})

describe('I-40 근처 가수 자리 밀어내기', () => {
  it('행성 바깥 궤도 안이면 그 밖(반지름+간격)으로, 밖이면 그대로', () => {
    const p = pushOut({ x: 110, y: 100 }, 8, [{ x: 100, y: 100, r: 30 }], 6)
    expect(p.x).toBeCloseTo(144); expect(p.y).toBeCloseTo(100)
    expect(pushOut({ x: 200, y: 100 }, 8, [{ x: 100, y: 100, r: 30 }], 6)).toEqual({ x: 200, y: 100 })
  })
  it('같은 자리면 위로, 여러 행성 사이에서도 모두 피한다', () => {
    expect(pushOut({ x: 50, y: 50 }, 5, [{ x: 50, y: 50, r: 10 }], 5)).toEqual({ x: 50, y: 30 })
    const obs = [{ x: 0, y: 0, r: 20 }, { x: 50, y: 0, r: 20 }]
    const q = pushOut({ x: 25, y: 1 }, 5, obs, 4)
    for (const o of obs) expect(Math.hypot(q.x - o.x, q.y - o.y)).toBeGreaterThanOrEqual(o.r + 5 + 4 - 1e-6)
  })
})
