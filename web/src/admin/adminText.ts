// 관리자 화면 문구 (감사 기록·상태·시각)
import type { AdminRoomRow, AuditView } from './adminApi'

const ACTIONS: Record<string, string> = {
  ROOM_CREATE: '우주 만들기 (이메일 인증)',
  ROOM_RENAME: '우주 이름 변경',
  ROOM_CLOSE: '새 참가 마감',
  ROOM_OPEN: '새 참가 다시 열기',
  ROOM_DELETE: '우주 삭제 (7일 뒤 완전 삭제)',
  PIN_RESET: '참가자 새 비밀번호 발급',
  MEMBER_KICK: '참가자 내보내기',
  HOST_RESET_SENT: '방장 비밀번호 재설정 코드 발송',
  HOST_PIN_RESET: '방장 비밀번호 재설정',
  ADMIN_LOGIN: '관리자 로그인',
  ADMIN_LOGIN_FAIL: '관리자 로그인 실패',
  ADMIN_ROOM_HIDE: '관리자가 숨김',
  ADMIN_ROOM_RESTORE: '관리자가 되살림',
  ADMIN_ROOM_RENAME: '관리자가 우주 이름 변경',
  ADMIN_MEMBER_RENAME: '관리자가 닉네임 변경',
  ADMIN_MEMBER_KICK: '관리자가 참가자 내보냄',
}
const ACTORS: Record<string, string> = { HOST: '방장', MEMBER: '참가자', ADMIN: '관리자', SYSTEM: '시스템' }

/** "11-13 14:02 · 방장 · 참가자 새 비밀번호 발급 (민지)" */
export function auditLine(a: AuditView, now: Date = new Date()): { when: string; text: string } {
  const d = a.detail ?? {}
  const str = (k: string) => (typeof d[k] === 'string' ? (d[k] as string) : null)
  const who = ACTORS[a.actorType] ?? a.actorType
  const what = ACTIONS[a.action] ?? a.action
  const extra = [
    str('oldNickname') && str('newNickname') ? `${str('oldNickname')} → ${str('newNickname')}` : str('nickname'),
    str('reason') ? `사유: ${str('reason')}` : null,
    a.roomId == null && str('roomName') ? `우주 '${str('roomName')}'` : null,
  ].filter(Boolean).join(' · ')
  return { when: shortTime(a.createdAt, now), text: `${who} · ${what}${extra ? ` (${extra})` : ''}` }
}

// 관리자 화면은 한국 운영자가 보므로 브라우저 시간대와 상관없이 한국 시간으로 보여 준다
const seoul = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
function seoulParts(d: Date) {
  const g = (type: string) => seoul.formatToParts(d).find(x => x.type === type)!.value
  return { y: g('year'), m: g('month'), d: g('day'), hh: g('hour'), mm: g('minute') }
}

/** 한국 시간 기준, 같은 해면 "11-13 14:02", 아니면 "2025-11-13" */
export function shortTime(iso: string, now: Date = new Date()): string {
  const t = seoulParts(new Date(iso))
  if (t.y !== seoulParts(now).y) return `${t.y}-${t.m}-${t.d}`
  return `${t.m}-${t.d} ${t.hh}:${t.mm}`
}

/** "방금" · "5분 전" · "2시간 전" · "어제" · "9일 전" · "3주 전" */
export function ago(iso: string, now: Date = new Date()): string {
  const s = (now.getTime() - new Date(iso).getTime()) / 1000
  if (s < 60) return '방금'
  if (s < 3600) return `${Math.floor(s / 60)}분 전`
  if (s < 86400) return `${Math.floor(s / 3600)}시간 전`
  const d = Math.floor(s / 86400)
  if (d === 1) return '어제'
  if (d < 14) return `${d}일 전`
  return `${Math.floor(d / 7)}주 전`
}

/** 목록 상태 표시: 활성 · 꽉 참 · 마감 · 숨김 · N일 뒤 삭제 */
export function statusLabel(r: AdminRoomRow, capacity = 5, now: Date = new Date()): { text: string; tone: 'normal' | 'gold' | 'muted' | 'danger' } {
  if (r.status === 'HIDDEN') {
    if (!r.purgeAt) return { text: '숨김', tone: 'danger' }
    const days = Math.max(0, Math.ceil((new Date(r.purgeAt).getTime() - now.getTime()) / 86400000))
    return { text: days === 0 ? '숨김 · 오늘 삭제' : `숨김 · ${days}일 뒤 삭제`, tone: 'danger' }
  }
  if (r.status === 'CLOSED') return { text: '마감', tone: 'muted' }
  if (r.memberCount >= capacity) return { text: '꽉 참', tone: 'gold' }
  return { text: '활성', tone: 'normal' }
}
