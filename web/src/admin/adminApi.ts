// 관리자 API (docs/todo/room-api-db.md 12절). 세션은 HttpOnly 쿠키(gyeol_admin, Path=/api/admin, SameSite=Strict)라
// 같은 출처 요청만 쓰고, 화면에는 아무 비밀값도 남기지 않는다.

export interface AdminMe { id: number; email: string; sessionExpiresAt: string; lastLoginAt: string | null }
export type AdminRoomStatus = 'ACTIVE' | 'CLOSED' | 'HIDDEN'
export interface AdminRoomRow {
  id: number; publicId: string; name: string; status: AdminRoomStatus; hostNickname: string | null
  memberCount: number; songCount: number; createdAt: string; lastActiveAt: string; purgeAt: string | null
}
export interface AdminMemberRow { id: number; nickname: string; role: 'HOST' | 'MEMBER'; songCount: number; createdAt: string; removedAt: string | null }
export interface AuditView { id: number; actorType: string; actorId: number | null; roomId: number | null; action: string; targetMemberId: number | null; detail: Record<string, unknown> | null; createdAt: string }
/** hostEmail은 가린 값(a***@g***.com)만 온다. 원문 보기는 없다 */
export interface AdminRoomDetail { room: AdminRoomRow; hostEmail: string | null; members: AdminMemberRow[]; audit: AuditView[] }
export type AdminSort = 'created' | 'active' | 'songs'

export class AdminApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message) }
}

async function call<T>(path: string, init: { method?: string; body?: unknown; signal?: AbortSignal } = {}): Promise<T> {
  let res: Response
  try {
    res = await fetch(`/api/admin${path}`, {
      method: init.method ?? 'GET',
      headers: init.body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      credentials: 'same-origin',
      signal: init.signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new AdminApiError(0, 'NETWORK', '서버에 연결하지 못했어요.')
  }
  if (res.status === 204) return undefined as T
  const body = await res.json().catch(() => null)
  if (!res.ok) throw new AdminApiError(res.status, body?.code ?? 'UNKNOWN', body?.message ?? '요청을 처리하지 못했어요.')
  return body as T
}

export const adminApi = {
  login: (email: string, password: string) => call<{ challenge: string; expiresAt: string }>('/login', { method: 'POST', body: { email, password } }),
  otp: (challenge: string, code: string) => call<AdminMe>('/login/otp', { method: 'POST', body: { challenge, code } }),
  logout: () => call<void>('/logout', { method: 'POST', body: {} }),
  me: (signal?: AbortSignal) => call<AdminMe>('/me', { signal }),
  rooms: (p: { q?: string; status?: string; sort?: AdminSort; page?: number; size?: number }, signal?: AbortSignal) => {
    const qs = new URLSearchParams()
    if (p.q) qs.set('q', p.q)
    if (p.status) qs.set('status', p.status)
    if (p.sort) qs.set('sort', p.sort)
    qs.set('page', String(p.page ?? 0))
    qs.set('size', String(p.size ?? 30))
    return call<{ items: AdminRoomRow[]; page: number; size: number }>(`/rooms?${qs}`, { signal })
  },
  room: (rid: string, signal?: AbortSignal) => call<AdminRoomDetail>(`/rooms/${encodeURIComponent(rid)}`, { signal }),
  hide: (rid: string, reason: string) => call<void>(`/rooms/${encodeURIComponent(rid)}/hide`, { method: 'POST', body: { reason } }),
  restore: (rid: string) => call<void>(`/rooms/${encodeURIComponent(rid)}/restore`, { method: 'POST', body: {} }),
  renameRoom: (rid: string, name: string) => call<void>(`/rooms/${encodeURIComponent(rid)}`, { method: 'PATCH', body: { name } }),
  renameMember: (rid: string, mid: number, nickname: string) => call<void>(`/rooms/${encodeURIComponent(rid)}/members/${mid}`, { method: 'PATCH', body: { nickname } }),
  kick: (rid: string, mid: number, reason: string) => call<void>(`/rooms/${encodeURIComponent(rid)}/members/${mid}`, { method: 'DELETE', body: { reason } }),
  audit: (p: { room?: string; page?: number; size?: number }, signal?: AbortSignal) => {
    const qs = new URLSearchParams({ page: String(p.page ?? 0), size: String(p.size ?? 50) })
    if (p.room) qs.set('room', p.room)
    return call<AuditView[]>(`/audit?${qs}`, { signal })
  },
}
