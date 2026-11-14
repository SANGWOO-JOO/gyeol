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
