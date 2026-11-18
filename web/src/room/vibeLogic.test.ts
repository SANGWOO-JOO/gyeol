import { describe, expect, it } from 'vitest'
import { RoomApiError } from './roomApi'
import { inRoomText, vibeErrorText, vibeQueryOk } from './vibeLogic'

describe('vibeQueryOk', () => {
  it('앞뒤 공백 빼고 2~100자', () => {
    expect(vibeQueryOk(' 비 ')).toBe(false)
    expect(vibeQueryOk('비 밤')).toBe(true)
    expect(vibeQueryOk('가'.repeat(100))).toBe(true)
    expect(vibeQueryOk('가'.repeat(101))).toBe(false)
  })
})

describe('vibeErrorText', () => {
  it('400·429는 정해진 문구', () => {
    expect(vibeErrorText(new RoomApiError(400, 'QUERY_LENGTH', 'x'))).toContain('2~100자')
    expect(vibeErrorText(new RoomApiError(429, 'DAILY_LIMIT', 'x'))).toContain('오늘은')
    expect(vibeErrorText(new Error('x'))).toContain('찾지 못했어요')
  })
})

describe('inRoomText', () => {
  it('사람 수와 받침에 맞게', () => {
    expect(inRoomText([])).toBeNull()
    expect(inRoomText(['민지'])).toBe('방에서 민지가 담음')
    expect(inRoomText(['상우', '지훈'])).toBe('방에서 상우·지훈이 담음')
    expect(inRoomText(['a', 'b', 'c'])).toBe('방에서 a 외 2명이 담음')
  })
})
