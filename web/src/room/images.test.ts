import { describe, expect, it } from 'vitest'
import { coverSources, nextIndex, sourcesOf } from './images'

describe('I-14 방 그림 후보', () => {
  it('저장본(방 주소)은 쿠키 → 열쇠, 외부 주소는 그대로, 빈 값·중복은 뺀다', () => {
    expect(coverSources({ coverPath: '/api/rooms/r/covers/3', coverUrl: 'https://coverartarchive.org/x' })).toEqual([
      { url: '/api/rooms/r/covers/3', mode: 'cookie' },
      { url: '/api/rooms/r/covers/3', mode: 'key' },
      { url: 'https://coverartarchive.org/x', mode: 'plain' },
    ])
    expect(coverSources({ coverPath: null, coverUrl: 'a.jpg' })).toEqual([{ url: 'a.jpg', mode: 'plain' }])
    expect(coverSources({ coverUrl: null })).toEqual([])
    expect(sourcesOf(['a.jpg', null, 'a.jpg', undefined])).toEqual([{ url: 'a.jpg', mode: 'plain' }])
  })
  it('방 주소는 쿠키가 되면 쿠키만, 안 되면 열쇠만', () => {
    const s = coverSources({ coverPath: '/api/rooms/r/covers/3', coverUrl: 'x' })
    expect(nextIndex(s, 0, true)).toBe(0)
    expect(nextIndex(s, 1, true)).toBe(2) // 쿠키로 실패(404)하면 열쇠로 다시 받지 않고 외부 표지로
    expect(nextIndex(s, 0, false)).toBe(1)
    expect(nextIndex(s, 2, false)).toBe(2)
    expect(nextIndex(s, 3, false)).toBe(3)
  })
})
