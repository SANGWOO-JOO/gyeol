import { describe, expect, it } from 'vitest'
import { lensGap, togglePick } from './playArt'

describe('놀이터 그림', () => {
  it('점수가 높을수록 두 행성이 가까워진다(더 겹침), 고르기 전은 반쯤', () => {
    expect(lensGap(50, null)).toBeCloseTo(55)
    expect(lensGap(50, 90)).toBeLessThan(lensGap(50, 40))
    expect(lensGap(50, 0)).toBeLessThan(100) // 0%여도 살짝 닿는다
    expect(lensGap(50, 100)).toBeGreaterThan(0)
  })
  it('두 명 고르기: 다시 누르면 빼고, 셋째면 먼저 고른 사람이 빠진다', () => {
    expect(togglePick([], 1)).toEqual([1])
    expect(togglePick([1], 2)).toEqual([1, 2])
    expect(togglePick([1, 2], 3)).toEqual([2, 3])
    expect(togglePick([1, 2], 1)).toEqual([2])
  })
})
