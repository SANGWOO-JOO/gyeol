// 음악 방 API (docs/todo/room-api-db.md 3절). 열쇠가 있으면 X-Room-Key 헤더로 보낸다.
import { keyStore } from './keyStore'

export type Role = 'HOST' | 'MEMBER'
export type RoomStatus = 'ACTIVE' | 'CLOSED' | 'HIDDEN'

export interface MemberView { id: number; nickname: string; role: Role; colorIdx: number; avatarCharacterId?: number | null }
export interface RoomSummary { roomId: string; name: string; status: RoomStatus; capacity: number; members: MemberView[] }
export interface DraftResponse { draftToken: string; email: string; expiresAt: string; resendAfter: string }
export interface JoinedResponse { roomId: string; key: string; member: MemberView }

/** 서버 오류: code는 화면 분기용, detail에 남은 시도 횟수·잠금 남은 초 등 */
export class RoomApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly detail: Record<string, unknown> | null = null,
  ) {
    super(message)
  }

  num(name: string): number | null {
    const v = this.detail?.[name]
    return typeof v === 'number' ? v : null
  }
}

interface CallOptions { method?: string; body?: unknown; roomId?: string; signal?: AbortSignal }

async function call<T>(path: string, opt: CallOptions = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (opt.body !== undefined) headers['Content-Type'] = 'application/json'
  const key = opt.roomId ? keyStore.get(opt.roomId)?.key : null
  if (key) headers['X-Room-Key'] = key
  let res: Response
  try {
    res = await fetch(`/api${path}`, {
      method: opt.method ?? 'GET',
      headers,
      body: opt.body === undefined ? undefined : JSON.stringify(opt.body),
      signal: opt.signal,
      credentials: 'same-origin',
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new RoomApiError(0, 'NETWORK', '서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.')
  }
  if (res.status === 204) return undefined as T
  const body = await res.json().catch(() => null)
  if (!res.ok) {
    throw new RoomApiError(res.status, body?.code ?? 'UNKNOWN', body?.message ?? '요청을 처리하지 못했어요.', body?.detail ?? null)
  }
  return body as T
}

const rid = (roomId: string) => encodeURIComponent(roomId)

export const roomApi = {
  createDraft: (b: { name: string; nickname: string; pin: string; email: string; consent: boolean }) =>
    call<DraftResponse>('/room-drafts', { method: 'POST', body: b }),
  resendDraft: (token: string) => call<DraftResponse>(`/room-drafts/${encodeURIComponent(token)}/resend`, { method: 'POST', body: {} }),
  verifyDraft: (token: string, code: string) =>
    call<JoinedResponse>(`/room-drafts/${encodeURIComponent(token)}/verify`, { method: 'POST', body: { code } }),

  summary: (roomId: string, signal?: AbortSignal) => call<RoomSummary>(`/rooms/${rid(roomId)}`, { signal }),
  join: (roomId: string, nickname: string, pin: string) =>
    call<JoinedResponse>(`/rooms/${rid(roomId)}/members`, { method: 'POST', body: { nickname, pin } }),
  rejoin: (roomId: string, memberId: number, pin: string) =>
    call<JoinedResponse>(`/rooms/${rid(roomId)}/session`, { method: 'POST', body: { memberId, pin } }),
  leave: (roomId: string) => call<void>(`/rooms/${rid(roomId)}/session`, { method: 'DELETE', roomId }),
}

/** 참가·다시 들어오기·방 만들기 성공 → 이 기기에 열쇠 저장 */
export function rememberJoin(j: JoinedResponse, roomName: string) {
  keyStore.save({ roomId: j.roomId, key: j.key, memberId: j.member.id, nickname: j.member.nickname, roomName })
}

/** 참가자 색 (colorIdx 0~4). 바탕색과 그 위 글자색 */
export const MEMBER_COLORS: { bg: string; fg: string }[] = [
  { bg: '#B9A2FF', fg: '#221a47' },
  { bg: '#E3A6B8', fg: '#3a1424' },
  { bg: '#FFE3B8', fg: '#3d2a10' },
  { bg: '#9ED8C8', fg: '#0f3329' },
  { bg: '#A8C8FF', fg: '#122a4d' },
]
export const memberColor = (idx: number) => MEMBER_COLORS[((idx % MEMBER_COLORS.length) + MEMBER_COLORS.length) % MEMBER_COLORS.length]
