import { describe, expect, it } from 'vitest'
import type { AdminRoomRow, AuditView } from './adminApi'
import { ago, auditLine, statusLabel } from './adminText'

const now = new Date('2026-11-13T15:00:00+09:00')
const audit = (over: Partial<AuditView>): AuditView => ({
  id: 1, actorType: 'HOST', actorId: 1, roomId: 1, action: 'PIN_RESET', targetMemberId: 2, detail: null, createdAt: '2026-11-13T14:02:00+09:00', ...over,
})
const row = (over: Partial<AdminRoomRow>): AdminRoomRow => ({
  id: 1, publicId: 'abc', name: '우주', status: 'ACTIVE', hostNickname: '상우', memberCount: 2, songCount: 8,
  createdAt: '2026-11-11T10:00:00+09:00', lastActiveAt: '2026-11-13T14:59:30+09:00', purgeAt: null, ...over,
})

describe('auditLine', () => {
  it('누가 무엇을 했는지 한국어로', () => {
    expect(auditLine(audit({}), now)).toEqual({ when: '11-13 14:02', text: '방장 · 참가자 새 비밀번호 발급' })
  })
  it('닉네임 변경·사유를 덧붙이고, 모르는 동작은 코드 그대로', () => {
    expect(auditLine(audit({ actorType: 'ADMIN', action: 'ADMIN_MEMBER_RENAME', detail: { oldNickname: '민지', newNickname: '민지2' } }), now).text)
      .toBe('관리자 · 관리자가 닉네임 변경 (민지 → 민지2)')
    expect(auditLine(audit({ actorType: 'ADMIN', action: 'ADMIN_ROOM_HIDE', detail: { reason: '스팸' } }), now).text).toBe('관리자 · 관리자가 숨김 (사유: 스팸)')
    expect(auditLine(audit({ action: 'NEW_THING' }), now).text).toBe('방장 · NEW_THING')
  })
  it('완전 삭제된 방의 기록은 방 이름을 남긴 값으로', () => {
    expect(auditLine(audit({ roomId: null, action: 'ROOM_DELETE', detail: { roomName: '새벽' } }), now).text).toBe("방장 · 우주 삭제 (7일 뒤 완전 삭제) (우주 '새벽')")
  })
})

describe('목록 표시', () => {
  it('마지막 활동', () => {
    expect(ago('2026-11-13T14:59:30+09:00', now)).toBe('방금')
    expect(ago('2026-11-13T13:00:00+09:00', now)).toBe('2시간 전')
    expect(ago('2026-11-12T10:00:00+09:00', now)).toBe('어제')
    expect(ago('2026-10-20T10:00:00+09:00', now)).toBe('3주 전')
  })
  it('상태', () => {
    expect(statusLabel(row({}), 5, now)).toEqual({ text: '활성', tone: 'normal' })
    expect(statusLabel(row({ memberCount: 5 }), 5, now).text).toBe('꽉 참')
    expect(statusLabel(row({ status: 'CLOSED' }), 5, now).text).toBe('마감')
    expect(statusLabel(row({ status: 'HIDDEN', purgeAt: '2026-11-18T15:00:00+09:00' }), 5, now)).toEqual({ text: '숨김 · 5일 뒤 삭제', tone: 'danger' })
  })
})
