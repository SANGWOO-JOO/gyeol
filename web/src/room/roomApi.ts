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
export interface GiftSummary {
  songCount: number; bestCount: number; nextMilestone: number; remaining: number; receivedToday: boolean; waiting: number; readyBoxId: number | null
  /** ⑯ 변경(11-14) 새 그림 하루 한도: 작업 세션이 더하는 중이라 없을 수 있음 */
  imagesLeftToday?: number | null; imageDailyLimit?: number | null
}
export interface MeView {
  roomId: string; roomName: string; roomStatus: RoomStatus; member: MemberView
  songCount: number; todayAdded: number; dailyLimit: number; pendingRequests: number; gift: GiftSummary | null
}

export interface TrackItem { title: string; artist: string; mbid: string | null; listeners: number | null; releaseYear: number | null; trackId: number | null; coverUrl: string | null; /** I-14 표지 저장본(방 열쇠 필요). 없으면 null */ coverPath?: string | null; /** 우리 가수로 묶인 곡 (I-30 ①) */ artistId?: number | null }
/** mine: 내가 이미 담음 · sameSong: 이 노래를 담은 사람 · sameArtist: 같은 가수의 다른 노래를 담은 사람 */
export interface RoomSearchItem { item: TrackItem; mine: boolean; sameSong: string[]; sameArtist: string[] }
/** 검색어가 가수와 맞을 때 결과 맨 위 가수 카드 (I-30 ①). DB에 없는 가수는 artistId 없음 */
export interface SearchArtist { artistId: number | null; name: string; aliases?: string[] | null; songCountInRoom?: number | null; onMap?: boolean | null; /** I-44 가수 화면 머리 사진 출처 */ photo?: PhotoCredit | null }
export interface SearchResponse { items: RoomSearchItem[]; source: string; artist?: SearchArtist | null }
/** 가수 화면 (I-30 ②③): 인기순·최신순 20곡씩 */
export type ArtistSort = 'popular' | 'latest'
export interface ArtistSongItem { item: TrackItem; mine: boolean; sameSong: string[]; rank?: number | null; releaseDate?: string | null }
export interface ArtistSongsPage {
  artist?: SearchArtist | null; sort: ArtistSort; page: number; pageSize: number; hasMore: boolean; items: ArtistSongItem[]
  /** 최신순 발매일 모으기 상태 (③). RUNNING·QUEUED면 모은 것만 와 있다 */
  scan?: 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | null
}
export interface SongView { id: number; trackId: number; title: string; artist: string; coverUrl: string | null; coverPath?: string | null; sharedCount: number }
export interface GiftNotice { boxId: number; milestone: number; status: string }
/** 201 ADDED(바로 담김) · 202 IDENTIFYING(식별 맡김, requestId로 확인) */
export interface AddSongResponse { status: 'ADDED' | 'IDENTIFYING'; song: SongView | null; requestId: number | null; todayAdded: number; dailyLimit: number; gift: GiftNotice | null }
/** failReason: NOT_FOUND · REJECTED · ERROR · MEMBER_REMOVED, DONE인데 ALREADY_ADDED면 이미 담은 노래 */
/** gift (I-51): 식별이 끝나 담기면서 50곡 단위 상자가 생겼을 때만 (201 응답의 gift와 같은 모양) */
export interface RequestView { id: number; status: 'IDENTIFYING' | 'DONE' | 'FAILED'; song: SongView | null; failReason: string | null; gift?: GiftNotice | null }

export interface MapSong { trackId: number; title: string; coverUrl: string | null; coverPath?: string | null; memberIds: number[] }
/** I-44 사진 출처 (Wikimedia Commons). 빈 값이 올 수 있다 */
export interface PhotoCredit { url?: string | null; author?: string | null; license?: string | null; pageUrl?: string | null }
/** I-44 행성 얼굴. PHOTO url은 방 열쇠가 필요한 /api/rooms/{rid}/artists/{id}/image (없으면 404 → 표지·장르색) */
export interface Face { kind: 'PHOTO' | 'COVER' | 'NONE' | string; url: string | null; galaxyId?: number | null; author?: string | null; license?: string | null; pageUrl?: string | null }
export interface Planet {
  artistId: number; name: string; x: number; y: number; songs: MapSong[]; galaxyId: number | null; preparing: boolean
  face?: Face | null
}
export interface Galaxy { id: number; name: string | null; x: number; y: number }
export interface Ghost { artistId: number; name: string; x: number; y: number; near: number; /** I-44 ⑤ PHOTO면 흐린 흑백 사진, NONE이면 이니셜 (COVER 없음) */ face?: Face | null; galaxyId?: number | null }
export interface RoomMap { rev: number; placeholder: boolean; members: MemberView[]; planets: Planet[]; galaxies: Galaxy[]; ghosts: Ghost[] }

export interface MemberRow { id: number; nickname: string; role: Role; colorIdx: number; songCount: number; me: boolean; avatarCharacterId?: number | null }
export interface MySong { id: number; trackId: number; title: string; artist: string; coverUrl: string | null; coverPath?: string | null; addedAt: string; sharedWith: number }
export interface MySongsPage { items: MySong[]; nextCursor: number | null }
export interface Evidence { id: number; source: string; url: string }
export interface ArtistView {
  artistId: number; name: string; description: string | null; evidence: Evidence[]
  /** I-44 얼굴과 사진 출처(Commons 사진일 때만) */
  face?: Face | null; photo?: PhotoCredit | null
  songs: MapSong[]; nearby: { artistId: number; name: string; inRoom: boolean }[]; preparing: boolean
}

export interface CodeMatchPerson { memberId: number; nickname: string; colorIdx: number; avatarCharacterId: number | null; songCount: number }
/** status: REASONING(이유 쓰는 중)·DONE·NO_REASON · confidence: LOW(5~9)·MEDIUM(10~19)·HIGH(20~) · stale: 그 뒤 곡·지도가 바뀜 */
export interface CodeMatchView {
  id: number; a: CodeMatchPerson; b: CodeMatchPerson; score: number
  parts: { genre: number; distance: number; artist: number; song: number }
  sharedArtists: string[]; sharedGalaxies: string[]; sharedSongs: number; sharedArtistCount: number; sharedGalaxyCount: number
  reason: string | null; evidence: Evidence[]; status: 'REASONING' | 'DONE' | 'NO_REASON'
  confidence: 'LOW' | 'MEDIUM' | 'HIGH'; minSongs: number; stale: boolean; createdAt: string
}

export type GiftStatus = 'WAITING' | 'READY' | 'GENERATING' | 'OPENED' | 'FAILED'
export interface GiftBoxView { id: number; milestone: number; status: GiftStatus; earnedAt: string; readyOn: string | null; openedAt: string | null; characterId: number | null }
export interface CharacterView { id: number; giftBoxId: number | null; isFallback: boolean; createdAt: string; fallbackReason?: 'DAILY_LIMIT' | 'FAILED' | string | null }
export interface GiftsResponse { summary: GiftSummary; boxes: GiftBoxView[]; characters: CharacterView[]; avatarCharacterId: number | null }
/** genreMix: OPENED 상자에만, 구역별 곡 수 상위 4개 (그 밖은 빈 배열) */
export interface GiftBoxDetail {
  box: GiftBoxView; character: CharacterView | null; genreMix?: { galaxy: string; songs: number }[]
  /** ⑯ 변경(11-14): 없을 수 있음 → 표시 생략 */
  imagesLeftToday?: number | null; imageDailyLimit?: number | null; fallbackReason?: 'DAILY_LIMIT' | 'FAILED' | string | null
}

/**
 * I-44 가수 사진: 방 열쇠가 있어야 받을 수 있어 SVG <image>에 주소를 바로 넣지 않고 헤더를 붙여 받아 blob 주소로 쓴다
 * (쿠키가 안 남는 브라우저 대비, 캐릭터 그림과 같은 방식). 못 받으면(401·404) null → 표지·장르색. 실패는 1분 기억해 다시 시도.
 */
const photoCache = new Map<string, { at: number; p: Promise<string | null> }>()
export function roomPhoto(roomId: string, url: string): Promise<string | null> {
  const hit = photoCache.get(url)
  if (hit) return hit.p
  const key = keyStore.get(roomId)?.key
  const p = fetch(url, { headers: key ? { 'X-Room-Key': key } : {}, credentials: 'same-origin' })
    .then(res => (res.ok ? res.blob().then(b => URL.createObjectURL(b)) : null))
    .catch(() => null)
  photoCache.set(url, { at: Date.now(), p })
  p.then(u => { if (!u) setTimeout(() => photoCache.delete(url), 60_000) })
  return p
}

/** 캐릭터 그림은 같은 방 참가자만 볼 수 있어 열쇠 헤더를 붙여 받고, 화면에는 blob 주소로 넣는다 (쿠키가 안 남는 브라우저 대비) */
const imageCache = new Map<string, Promise<string | null>>()
export function characterImage(roomId: string, characterId: number): Promise<string | null> {
  const k = `${roomId}/${characterId}`
  let p = imageCache.get(k)
  if (!p) {
    const key = keyStore.get(roomId)?.key
    p = fetch(`/api/rooms/${rid(roomId)}/characters/${characterId}/image`, { headers: key ? { 'X-Room-Key': key } : {}, credentials: 'same-origin' })
      .then(res => (res.ok ? res.blob().then(b => URL.createObjectURL(b)) : null))
      .catch(() => null)
    p.then(url => { if (!url) imageCache.delete(k) })
    imageCache.set(k, p)
  }
  return p
}

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
    call<SearchResponse>(`/rooms/${rid(roomId)}/search?q=${encodeURIComponent(q)}`, { roomId, signal }),
  /** 가수 노래 전체. DB 가수는 artistId로, 아직 DB에 없는 가수(카드의 artistId 없음)는 이름으로 */
  artistSongs: (roomId: string, a: { artistId: number | null; name: string }, sort: ArtistSort, page: number, signal?: AbortSignal) =>
    call<ArtistSongsPage>(a.artistId != null
      ? `/rooms/${rid(roomId)}/artists/${a.artistId}/songs?sort=${sort}&page=${page}`
      : `/rooms/${rid(roomId)}/artist-songs?name=${encodeURIComponent(a.name)}&sort=${sort}&page=${page}`, { roomId, signal }),
  addSong: (roomId: string, t: TrackItem) =>
    call<AddSongResponse>(`/rooms/${rid(roomId)}/songs`, {
      method: 'POST', roomId,
      body: t.trackId != null ? { trackId: t.trackId } : { title: t.title, artist: t.artist, mbid: t.mbid },
    }),
  songRequest: (roomId: string, id: number, signal?: AbortSignal) => call<RequestView>(`/rooms/${rid(roomId)}/song-requests/${id}`, { roomId, signal }),
  removeSong: (roomId: string, songId: number) => call<void>(`/rooms/${rid(roomId)}/songs/${songId}`, { method: 'DELETE', roomId }),
  map: (roomId: string, etag: string | null, signal?: AbortSignal) => fetchMap(roomId, etag, signal),
  members: (roomId: string, signal?: AbortSignal) => call<MemberRow[]>(`/rooms/${rid(roomId)}/members`, { roomId, signal }),
  mySongs: (roomId: string, cursor: number | null, limit = 30, signal?: AbortSignal) =>
    call<MySongsPage>(`/rooms/${rid(roomId)}/me/songs?limit=${limit}${cursor != null ? `&cursor=${cursor}` : ''}`, { roomId, signal }),
  artist: (roomId: string, artistId: number, signal?: AbortSignal) => call<ArtistView>(`/rooms/${rid(roomId)}/artists/${artistId}`, { roomId, signal }),
  // ── 5단계: 내 정보·방장 메뉴·방장 암호 재설정 ──
  rename: (roomId: string, nickname: string) => call<MemberView>(`/rooms/${rid(roomId)}/me`, { method: 'PATCH', roomId, body: { nickname } }),
  resetPin: (roomId: string, memberId: number) => call<{ memberId: number; pin: string }>(`/rooms/${rid(roomId)}/members/${memberId}/pin`, { method: 'POST', roomId, body: {} }),
  kick: (roomId: string, memberId: number) => call<void>(`/rooms/${rid(roomId)}/members/${memberId}`, { method: 'DELETE', roomId }),
  patchRoom: (roomId: string, patch: { name?: string; closed?: boolean }) => call<RoomSummary>(`/rooms/${rid(roomId)}`, { method: 'PATCH', roomId, body: patch }),
  deleteRoom: (roomId: string) => call<void>(`/rooms/${rid(roomId)}`, { method: 'DELETE', roomId }),
  /** 방장 암호 재설정: 등록 이메일로 코드 (주소는 응답에 없음) */
  hostReset: (roomId: string) => call<{ codeExpiresAt: string; resendAfter: string }>(`/rooms/${rid(roomId)}/host-reset`, { method: 'POST', body: {} }),
  hostResetVerify: (roomId: string, code: string, newPin: string) =>
    call<JoinedResponse>(`/rooms/${rid(roomId)}/host-reset/verify`, { method: 'POST', body: { code, newPin } }),
  // ── 6단계: 50곡 선물 (room-api-db.md 9절) ──
  gifts: (roomId: string, signal?: AbortSignal) => call<GiftsResponse>(`/rooms/${rid(roomId)}/me/gifts`, { roomId, signal }),
  giftBox: (roomId: string, boxId: number, signal?: AbortSignal) => call<GiftBoxDetail>(`/rooms/${rid(roomId)}/me/gifts/${boxId}`, { roomId, signal }),
  openGift: (roomId: string, boxId: number) => call<GiftBoxDetail>(`/rooms/${rid(roomId)}/me/gifts/${boxId}/open`, { method: 'POST', roomId, body: {} }),
  setAvatar: (roomId: string, characterId: number | null) => call<MemberView>(`/rooms/${rid(roomId)}/me/avatar`, { method: 'PUT', roomId, body: { characterId } }),
  // ── 7단계: 놀이터 코드 맞추기 (room-api-db.md 11절). 저장 결과 200, 새 계산 202 ──
  codeMatch: (roomId: string, a: number, b: number) => call<CodeMatchView>(`/rooms/${rid(roomId)}/code-match`, { method: 'POST', roomId, body: { a, b } }),
  codeMatchLatest: (roomId: string, a: number, b: number, signal?: AbortSignal) => call<CodeMatchView>(`/rooms/${rid(roomId)}/code-match?a=${a}&b=${b}`, { roomId, signal }),
  /** 되돌리기로 다시 담을 때 (trackId만) */
  addTrack: (roomId: string, trackId: number) => call<AddSongResponse>(`/rooms/${rid(roomId)}/songs`, { method: 'POST', roomId, body: { trackId } }),
}

/** 지도는 ETag(map_rev)로 바뀌었을 때만 받는다. 바뀌지 않았으면 null */
async function fetchMap(roomId: string, etag: string | null, signal?: AbortSignal): Promise<{ map: RoomMap; etag: string | null } | null> {
  const headers: Record<string, string> = {}
  const key = keyStore.get(roomId)?.key
  if (key) headers['X-Room-Key'] = key
  if (etag) headers['If-None-Match'] = etag
  let res: Response
  try {
    // ETag는 우리가 직접 다룬다. 브라우저 캐시가 스스로 붙인 If-None-Match로 옛 본문을 돌려주지 않게 no-store
    res = await fetch(`/api/rooms/${rid(roomId)}/map`, { headers, signal, credentials: 'same-origin', cache: 'no-store' })
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
