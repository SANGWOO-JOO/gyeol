// 음악 방 API (docs/todo/room-api-db.md 3절). 열쇠가 있으면 X-Room-Key 헤더로 보낸다.
import { keyStore } from './keyStore'

export type Role = 'HOST' | 'MEMBER'
export type RoomStatus = 'ACTIVE' | 'CLOSED' | 'HIDDEN'

export interface MemberView { id: number; nickname: string; role: Role; colorIdx: number; avatarCharacterId?: number | null }
export interface RoomSummary { roomId: string; name: string; status: RoomStatus; capacity: number; members: MemberView[] }
/** expiresAt: 초안(1시간) 만료 · codeExpiresAt: 지금 코드(10분) 만료 (서버 4552764부터) */
export interface DraftResponse { draftToken: string; email: string; expiresAt: string; resendAfter: string; codeExpiresAt?: string | null }
export interface JoinedResponse { roomId: string; key: string; member: MemberView }

/** ④ 선물 요약 (6단계에서 씀) */
export interface GiftSummary { songCount: number; bestCount: number; nextMilestone: number; remaining: number; receivedToday: boolean; waiting: number; readyBoxId: number | null }
export interface MeView {
  roomId: string; roomName: string; roomStatus: RoomStatus; member: MemberView
  songCount: number; todayAdded: number; dailyLimit: number; pendingRequests: number; gift: GiftSummary | null
}

export interface TrackItem { title: string; artist: string; mbid: string | null; listeners: number | null; releaseYear: number | null; trackId: number | null; coverUrl: string | null }
/** mine: 내가 이미 담음 · sameSong: 이 노래를 담은 사람 · sameArtist: 같은 가수의 다른 노래를 담은 사람 */
export interface RoomSearchItem { item: TrackItem; mine: boolean; sameSong: string[]; sameArtist: string[] }
export interface SongView { id: number; trackId: number; title: string; artist: string; coverUrl: string | null; sharedCount: number }
export interface GiftNotice { boxId: number; milestone: number; status: string }
/** 201 ADDED(바로 담김) · 202 IDENTIFYING(식별 맡김, requestId로 확인) */
export interface AddSongResponse { status: 'ADDED' | 'IDENTIFYING'; song: SongView | null; requestId: number | null; todayAdded: number; dailyLimit: number; gift: GiftNotice | null }
/** failReason: NOT_FOUND · REJECTED · ERROR · MEMBER_REMOVED, DONE인데 ALREADY_ADDED면 이미 담은 노래 */
export interface RequestView { id: number; status: 'IDENTIFYING' | 'DONE' | 'FAILED'; song: SongView | null; failReason: string | null }

export interface MapSong { trackId: number; title: string; coverUrl: string | null; memberIds: number[] }
export interface Planet { artistId: number; name: string; x: number; y: number; songs: MapSong[]; galaxyId: number | null; preparing: boolean }
export interface Galaxy { id: number; name: string | null; x: number; y: number }
export interface Ghost { artistId: number; name: string; x: number; y: number; near: number }
export interface RoomMap { rev: number; placeholder: boolean; members: MemberView[]; planets: Planet[]; galaxies: Galaxy[]; ghosts: Ghost[] }

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

  me: (roomId: string, signal?: AbortSignal) => call<MeView>(`/rooms/${rid(roomId)}/me`, { roomId, signal }),
  search: (roomId: string, q: string, signal?: AbortSignal) =>
    call<{ items: RoomSearchItem[]; source: string }>(`/rooms/${rid(roomId)}/search?q=${encodeURIComponent(q)}`, { roomId, signal }),
  addSong: (roomId: string, t: TrackItem) =>
    call<AddSongResponse>(`/rooms/${rid(roomId)}/songs`, {
      method: 'POST', roomId,
      body: t.trackId != null ? { trackId: t.trackId } : { title: t.title, artist: t.artist, mbid: t.mbid },
    }),
  songRequest: (roomId: string, id: number, signal?: AbortSignal) => call<RequestView>(`/rooms/${rid(roomId)}/song-requests/${id}`, { roomId, signal }),
  removeSong: (roomId: string, songId: number) => call<void>(`/rooms/${rid(roomId)}/songs/${songId}`, { method: 'DELETE', roomId }),
  map: (roomId: string, etag: string | null, signal?: AbortSignal) => fetchMap(roomId, etag, signal),
}

/** 지도는 ETag(map_rev)로 바뀌었을 때만 받는다. 바뀌지 않았으면 null */
async function fetchMap(roomId: string, etag: string | null, signal?: AbortSignal): Promise<{ map: RoomMap; etag: string | null } | null> {
  const headers: Record<string, string> = {}
  const key = keyStore.get(roomId)?.key
  if (key) headers['X-Room-Key'] = key
  if (etag) headers['If-None-Match'] = etag
  let res: Response
  try {
    res = await fetch(`/api/rooms/${rid(roomId)}/map`, { headers, signal, credentials: 'same-origin' })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new RoomApiError(0, 'NETWORK', '서버에 연결하지 못했어요.')
  }
  if (res.status === 304) return null
  const body = await res.json().catch(() => null)
  if (!res.ok) throw new RoomApiError(res.status, body?.code ?? 'UNKNOWN', body?.message ?? '지도를 불러오지 못했어요.', body?.detail ?? null)
  return { map: body as RoomMap, etag: res.headers.get('ETag') }
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
