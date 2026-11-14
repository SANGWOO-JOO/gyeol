import { describe, expect, it } from 'vitest'
import { giftNoticeToast } from './giftNotice'

describe('I-51 상자 알림', () => {
  it('WAITING 상자만 알린다', () => {
    expect(giftNoticeToast({ boxId: 1, milestone: 100, status: 'WAITING' }, new Set())).toEqual({ title: '100곡 선물 상자가 생겼어요', sub: '오늘 선물은 이미 받아서 내일 열려요' })
    expect(giftNoticeToast({ boxId: 1, milestone: 50, status: 'READY' }, new Set())).toBeNull()
  })
  it('gift가 없으면 알리지 않는다 (예전 API 응답 포함)', () => {
    expect(giftNoticeToast(null, new Set())).toBeNull()
    expect(giftNoticeToast(undefined, new Set())).toBeNull()
  })
  it('이미 알린 상자는 다시 알리지 않는다', () => {
    expect(giftNoticeToast({ boxId: 7, milestone: 150, status: 'WAITING' }, new Set([7]))).toBeNull()
    expect(giftNoticeToast({ boxId: 8, milestone: 200, status: 'WAITING' }, new Set([7]))).not.toBeNull()
  })
})
