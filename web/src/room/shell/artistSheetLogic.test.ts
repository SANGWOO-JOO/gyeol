import { describe, expect, it } from 'vitest'
import { roomSummary, sharedFirst } from './artistSheetLogic'
import { factChips } from './ArtistSheet'

describe('가수 판', () => {
  it('우리 방 요약: 사람별 혼자 담은 곡, 같이 담은 곡', () => {
    const songs = [{ memberIds: [2] }, { memberIds: [1, 2] }, { memberIds: [1] }, { memberIds: [1] }, { memberIds: [2, 1] }]
    expect(roomSummary(songs, [1, 2])).toEqual({ solo: [{ memberId: 1, count: 2 }, { memberId: 2, count: 1 }], shared: 2, total: 5 })
  })
  it('같이 담은 곡이 맨 위(사람 많은 순), 나머지는 원래 순서', () => {
    const songs = [{ id: 'a', memberIds: [1] }, { id: 'b', memberIds: [1, 2] }, { id: 'c', memberIds: [2] }, { id: 'd', memberIds: [1, 2, 3] }]
    expect(sharedFirst(songs).map(s => s.id)).toEqual(['d', 'b', 'a', 'c'])
  })
})

describe('사실 줄 칩', () => {
  it('지역 + 태그, 빈 값 빼고 최대 8개', () => {
    expect(factChips({ area: '한국', tags: ['J-pop', 'K-pop', ''] })).toEqual(['한국', 'J-pop', 'K-pop'])
    expect(factChips(null)).toEqual([])
    expect(factChips({ tags: Array.from({ length: 10 }, (_, i) => `t${i}`) })).toHaveLength(8)
  })
})

import { ringArcs, roomPills } from './artistSheetLogic'
describe('우리 방 알약·고리', () => {
  const A = 1, B = 2, C = 3, D = 4, E = 5
  it('2명: 사람 수는 같이 담은 곡 포함, 같이 n은 한 번', () => {
    const songs = [{ memberIds: [A] }, { memberIds: [A] }, { memberIds: [A] }, { memberIds: [A] }, { memberIds: [A, B] }, { memberIds: [B, A] }, { memberIds: [B] }, { memberIds: [B] }, { memberIds: [B] }]
    const r = roomPills(songs, [A, B], A)
    expect(r.people).toEqual([{ memberId: A, count: 6 }, { memberId: B, count: 5 }])
    expect(r.shared).toBe(2)
  })
  it('4명: 같이 조합 A+B, C+D, A+B+C, 같이만 담은 사람도 알약', () => {
    const songs = [{ memberIds: [A, B] }, { memberIds: [C, D] }, { memberIds: [A, B, C] }, { memberIds: [A] }, { memberIds: [B] }, { memberIds: [B] }]
    const r = roomPills(songs, [A, B, C, D], A)
    expect(r.people).toEqual([{ memberId: A, count: 3 }, { memberId: B, count: 4 }, { memberId: C, count: 2 }, { memberId: D, count: 1 }])
    expect(r.shown.map(x => x.memberId)).toEqual([A, B, C])
    expect(r.hidden).toBe(1)
    expect(r.shared).toBe(3)
  })
  it('나 없음: 나 알약 없이 곡 많은 순, 같으면 참가 순', () => {
    const r = roomPills([{ memberIds: [C] }, { memberIds: [B] }, { memberIds: [B] }, { memberIds: [D] }], [B, C, D], A)
    expect(r.people.map(x => x.memberId)).toEqual([B, C, D])
  })
  it('5명: 3명까지 보이고 +2', () => {
    const r = roomPills([A, B, C, D, E].map(id => ({ memberIds: [id] })), [A, B, C, D, E], C)
    expect(r.shown.map(x => x.memberId)).toEqual([C, A, B])
    expect(r.hidden).toBe(2)
  })
  it('고리: 최소 10°, 틈 3°, 합이 한 바퀴', () => {
    const arcs = ringArcs([100, 1, 1])
    expect(Math.min(...arcs.map(a => a.sweep))).toBeGreaterThanOrEqual(10 - 1e-9)
    expect(arcs.reduce((s, a) => s + a.sweep, 0) + 3 * 3).toBeCloseTo(360)
    expect(ringArcs([5])).toEqual([{ start: 0, sweep: 360 }])
    expect(ringArcs([])).toEqual([])
  })
})
