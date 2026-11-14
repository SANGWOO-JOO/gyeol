import { describe, expect, it } from 'vitest'
import { keepFocus, planetLit, ringLit, songLit, toggleFocus } from './focus'

describe('I-26 사람 선택', () => {
  it('누르면 선택, 같은 사람을 다시 누르면 해제, 다른 사람이면 바뀐다', () => {
    expect(toggleFocus(null, 3)).toBe(3)
    expect(toggleFocus(3, 3)).toBeNull()
    expect(toggleFocus(3, 5)).toBe(5)
  })

  it('선택된 사람이 방에서 나가면 해제', () => {
    expect(keepFocus(3, [1, 3])).toBe(3)
    expect(keepFocus(3, [1, 2])).toBeNull()
    expect(keepFocus(null, [1])).toBeNull()
  })

  it('선택이 없으면 모두 밝다', () => {
    expect(songLit([1], null)).toBe(true)
    expect(planetLit({ songs: [{ memberIds: [1] }] }, null)).toBe(true)
    expect(ringLit({ kind: 'person', memberIds: [1], sats: [] }, null)).toBe(true)
  })

  it('곡은 선택된 사람이 담았을 때만 밝다 (같이 담은 곡 포함)', () => {
    expect(songLit([1], 1)).toBe(true)
    expect(songLit([1, 2], 2)).toBe(true)
    expect(songLit([1], 2)).toBe(false)
  })

  it('여러 사람이 담은 행성: 선택된 사람 곡이 하나라도 있으면 밝다', () => {
    const p = { songs: [{ memberIds: [1] }, { memberIds: [2] }, { memberIds: [1, 3] }] }
    expect(planetLit(p, 1)).toBe(true)
    expect(planetLit(p, 2)).toBe(true)
    expect(planetLit(p, 3)).toBe(true)
    expect(planetLit(p, 4)).toBe(false)
  })

  it('궤도: 사람 궤도는 그 사람 것만, 금빛 궤도는 밝은 위성이 있을 때만', () => {
    expect(ringLit({ kind: 'person', memberIds: [1], sats: [{ memberIds: [1] }] }, 1)).toBe(true)
    expect(ringLit({ kind: 'person', memberIds: [1], sats: [{ memberIds: [1] }] }, 2)).toBe(false)
    const gold = { kind: 'gold', memberIds: [1, 2, 3], sats: [{ memberIds: [1, 2] }] }
    expect(ringLit(gold, 2)).toBe(true)
    expect(ringLit(gold, 3)).toBe(false)
  })
})
