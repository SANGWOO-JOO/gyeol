import { describe, expect, it } from 'vitest'
import { clampPanelWidth, defaultPanelWidth, dragPanelWidth, expandsPanel, keepCenterShift, keyPanelWidth, PANEL_KEY, PANEL_MIN, PANEL_STEP, PANEL_WIDTH_KEY, panelMax, panelWidthFor, readCollapsed, readPanelWidth, writeCollapsed, writePanelWidth } from './panelPref'

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

describe('PC 판 너비 조절', () => {
  const mem = () => { const m = new Map<string, string>(); return { m, s: { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v) }, removeItem: (k: string) => { m.delete(k) } } } }

  it('기본: 1600px 이상 440, 미만 400 (화면 40%를 넘지 않게)', () => {
    expect(defaultPanelWidth(1920)).toBe(440)
    expect(defaultPanelWidth(1600)).toBe(440)
    expect(defaultPanelWidth(1280)).toBe(400)
    expect(defaultPanelWidth(900)).toBe(360)
  })
  it('범위: 320~560, 화면 40% 상한, 좁은 화면은 320 고정', () => {
    expect(panelMax(1920)).toBe(560)
    expect(panelMax(1280)).toBe(512)
    expect(panelMax(700)).toBe(320)
    expect(clampPanelWidth(100, 1920)).toBe(320)
    expect(clampPanelWidth(999, 1920)).toBe(560)
    expect(clampPanelWidth(600, 1280)).toBe(512)
    expect(clampPanelWidth(Number.NaN, 1920)).toBe(320)
    expect(clampPanelWidth(433.6, 1920)).toBe(434)
  })
  it('끌기: 왼쪽으로 끌면 넓어지고, 범위에서 멈춘다', () => {
    expect(dragPanelWidth(400, 1500, 1400, 1920)).toBe(500)
    expect(dragPanelWidth(400, 1500, 1600, 1920)).toBe(320)
    expect(dragPanelWidth(400, 1500, 1000, 1920)).toBe(560)
  })
  it('키보드: ← 넓게 16px, → 좁게, Home·End, 다른 키 null', () => {
    expect(keyPanelWidth(400, 'ArrowLeft', 1920)).toBe(400 + PANEL_STEP)
    expect(keyPanelWidth(400, 'ArrowRight', 1920)).toBe(400 - PANEL_STEP)
    expect(keyPanelWidth(552, 'ArrowLeft', 1920)).toBe(560)
    expect(keyPanelWidth(400, 'Home', 1920)).toBe(PANEL_MIN)
    expect(keyPanelWidth(400, 'End', 1280)).toBe(512)
    expect(keyPanelWidth(400, 'Enter', 1920)).toBeNull()
  })
  it('기억: 저장·읽기·지우기, 잘못된 값과 고장 난 저장소는 null', () => {
    const { m, s } = mem()
    expect(readPanelWidth(s)).toBeNull()
    writePanelWidth(480.4, s); expect(m.get(PANEL_WIDTH_KEY)).toBe('480'); expect(readPanelWidth(s)).toBe(480)
    writePanelWidth(null, s); expect(readPanelWidth(s)).toBeNull()
    m.set(PANEL_WIDTH_KEY, 'abc'); expect(readPanelWidth(s)).toBeNull()
    const broken = { getItem: () => { throw new Error('x') }, setItem: () => { throw new Error('x') } }
    expect(readPanelWidth(broken)).toBeNull()
    expect(() => writePanelWidth(400, broken)).not.toThrow()
  })
  it('보여 줄 너비: 기억한 값을 이 화면에 맞추고, 없으면 기본값', () => {
    expect(panelWidthFor(1920, null)).toBe(440)
    expect(panelWidthFor(1280, 540)).toBe(512)
    expect(panelWidthFor(1920, 540)).toBe(540)
  })
})
