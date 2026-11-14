import { describe, expect, it } from 'vitest'
import { SAT_TIP_MAX, satTipPosition, satTipWidth } from './satTip'

describe('위성 이름표 위치', () => {
  const view = { width: 800, height: 600 }
  it('보통은 위성 위 가운데, 꼬리는 위성을 가리킨다', () => {
    const p = satTipPosition({ x: 400, y: 300 }, 100, view)
    expect(p).toEqual({ left: 350, top: 260, tailX: 50, below: false })
  })
  it('가장자리에서는 화면 안으로 밀고 꼬리는 위성 쪽에 남는다', () => {
    const l = satTipPosition({ x: 20, y: 300 }, 120, view)
    expect(l.left).toBe(8)
    expect(l.tailX).toBe(14)
    const r = satTipPosition({ x: 790, y: 300 }, 120, view)
    expect(r.left + 120).toBe(792)
    expect(r.tailX).toBe(120 - 14)
  })
  it('위가 모자라면 아래로 뒤집는다', () => {
    expect(satTipPosition({ x: 400, y: 30 }, 100, view)).toMatchObject({ top: 40, below: true })
  })
  it('폭은 글자 수로 추정하고 최대 폭에서 멈춘다', () => {
    expect(satTipWidth('태양물고기')).toBeLessThan(satTipWidth('태양물고기 태양물고기'))
    expect(satTipWidth('가'.repeat(40))).toBe(SAT_TIP_MAX)
  })
})
