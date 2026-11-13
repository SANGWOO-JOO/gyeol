import { describe, expect, it } from 'vitest'
import { defaultRoomName, hasBatchim, iAm, minutesText, mmss } from './text'

describe('iAm', () => {
  it('받침이 있으면 이에요, 없으면 예요', () => {
    expect(iAm('민지')).toBe('나는 민지예요')
    expect(iAm('상우')).toBe('나는 상우예요')
    expect(iAm('지훈')).toBe('나는 지훈이에요')
  })
  it('한글이 아니면 받침 없음으로 본다', () => {
    expect(hasBatchim('Sam')).toBe(false)
    expect(iAm('Sam')).toBe('나는 Sam예요')
    expect(hasBatchim('')).toBe(false)
  })
})

describe('시간 문구', () => {
  it('mmss', () => {
    expect(mmss(545)).toBe('9:05')
    expect(mmss(59.2)).toBe('1:00')
    expect(mmss(-3)).toBe('0:00')
  })
  it('minutesText', () => {
    expect(minutesText(600)).toBe('10분')
    expect(minutesText(30)).toBe('1분')
  })
})

describe('defaultRoomName', () => {
  it('닉네임으로 짓는다', () => {
    expect(defaultRoomName(' 상우 ')).toBe('상우의 우주')
  })
})
