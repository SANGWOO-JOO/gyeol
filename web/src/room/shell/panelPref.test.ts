import { describe, expect, it } from 'vitest'
import { expandsPanel, keepCenterShift, PANEL_KEY, readCollapsed, writeCollapsed } from './panelPref'

describe('PC 판 접기', () => {
  it('기억: 저장소에 1/0, 못 쓰면 펼침', () => {
    const m = new Map<string, string>()
    const s = { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v) } }
    expect(readCollapsed(s)).toBe(false)
    writeCollapsed(true, s); expect(m.get(PANEL_KEY)).toBe('1'); expect(readCollapsed(s)).toBe(true)
    const broken = { getItem: () => { throw new Error('x') }, setItem: () => { throw new Error('x') } }
    expect(readCollapsed(broken)).toBe(false)
    expect(() => writeCollapsed(true, broken)).not.toThrow()
  })
  it('저절로 펼침: 탭 고름, 가수·내 정보 판', () => {
    expect(expandsPanel({ kind: 'tab' })).toBe(true)
    expect(expandsPanel({ kind: 'sheet', sheet: 'artist' })).toBe(true)
    expect(expandsPanel({ kind: 'sheet', sheet: 'settings' })).toBe(false)
    expect(expandsPanel({ kind: 'sheet', sheet: null })).toBe(false)
  })
  it('가로만 바뀌면 중심 유지 이동량, 세로도 바뀌면 null', () => {
    expect(keepCenterShift({ width: 1040, height: 800 }, { width: 1380, height: 800 })).toBe(170)
    expect(keepCenterShift({ width: 1380, height: 800 }, { width: 1040, height: 800 })).toBe(-170)
    expect(keepCenterShift({ width: 390, height: 700 }, { width: 700, height: 390 })).toBeNull()
    expect(keepCenterShift(null, { width: 10, height: 10 })).toBeNull()
  })
})
