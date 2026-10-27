// api 서버 호출 (docs/l1-design.md 4-1). 개발 서버는 /api를 18080으로 넘긴다

export type Entry = 'SEEDS' | 'TAKEOUT'
export type Status = 'CREATED' | 'UPLOADING' | 'PROCESSING' | 'DONE' | 'FAILED'

/** trackId·coverUrl은 우리 DB에서 찾은 곡에만 있다 (L2-4). trackId가 있으면 고를 때 바로 곡에 연결된다 */
export interface Track { title: string; artist: string; mbid?: string | null; listeners?: number | null; releaseYear?: number | null; trackId?: number | null; coverUrl?: string | null }
export interface CreatedSession { token: string; entry: Entry; status: Status; resultUrl: string; expiresAt: string }
export interface SessionView {
  entry: Entry
  status: Status
  counts: { total: number | null; music: number | null; unclassified: number | null; saved: number | null }
  period: { start: string | null; end: string | null } | null
  seeds: { mbid: string | null; title: string; artist: string; releaseTitle?: string | null; releaseYear?: number | null; coverUrl?: string | null }[] | null
  /** L2-1 곡 식별 진행률 (재생 상위 곡). 대상이 없으면 null */
  identify: { target: number; done: number; matched: number; notFound: number; failed: number } | null
  /** L3-1 곡 정보(지식) 모으기 진행률. 대상이 없으면 null */
  enrich: { target: number; done: number; failed: number } | null
  error: string | null
  createdAt: string
  expiresAt: string
}
export interface UploadTicket { uploadId: string; url: string; method: 'PUT'; headers: Record<string, string>; expiresAt: string }
export interface Stats {
  period: { start: string | null; end: string | null; days: number | null }
  totals: { plays: number; uniqueTitles: number; uniqueChannels: number }
  topTitles: { title: string; channel: string | null; plays: number }[]
  topChannels: { channel: string; plays: number }[]
  byHour: number[]
  byWeekday: number[]
  byMonth: { month: string; plays: number }[]
  bySource: { musicApp: number; youtube: number }
  coverage: { music: number | null; unclassified: number | null }
  /** L2-2 곡 단위 순위. trackId가 없으면 아직 못 찾은 곡 ("제목 + 채널" 그대로) */
  topSongs: { trackId: number | null; title: string; artist: string | null; release: { title: string | null; year: number | null } | null; coverUrl: string | null; plays: number }[]
  topArtists: { artistId: number | null; name: string; plays: number }[]
  identified: { plays: number; totalPlays: number }
}

/** L3-4 추천. status: NONE(시작 전) · CANDIDATES(후보 곡 정보 모으는 중) · READY */
export interface Recommendations {
  status: 'NONE' | 'CANDIDATES' | 'READY'
  candidatesDone: number
  candidatesTotal: number
  summary: string | null
  model: string | null
  items: {
    rank: number; trackId: number; title: string; artist: string; releaseTitle: string | null; coverUrl: string | null; score: number
    reason: string | null; evidence: { id: number; source: string; url: string }[]; basedOn: { trackId: number; title: string; artist: string }[]
  }[]
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message) }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(`/api${path}`, { ...init, headers: init?.body ? { 'Content-Type': 'application/json' } : undefined })
  } catch {
    throw new ApiError(0, 'NETWORK', '서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요.')
  }
  if (res.status === 204) return undefined as T
  const body = await res.json().catch(() => null)
  if (!res.ok) throw new ApiError(res.status, body?.code ?? 'UNKNOWN', body?.message ?? '요청을 처리하지 못했습니다.')
  return body as T
}

const json = (method: string, body: unknown): RequestInit => ({ method, body: JSON.stringify(body) })

export const api = {
  createSession: (entry: Entry) => call<CreatedSession>('/sessions', json('POST', { entry })),
  session: (token: string) => call<SessionView>(`/sessions/${token}`),
  searchTracks: (q: string, signal?: AbortSignal) =>
    call<{ items: Track[]; source: string }>(`/tracks/search?q=${encodeURIComponent(q)}&limit=10`, { signal }),
  saveSeeds: (token: string, tracks: Track[]) =>
    call<{ status: string; count: number }>(`/sessions/${token}/seeds`, json('POST', {
      tracks: tracks.map(t => ({ mbid: t.mbid ?? null, title: t.title, artist: t.artist, trackId: t.trackId ?? null })),
    })),
  uploadUrl: (token: string, c: { totalCount: number; musicCount: number; unclassifiedCount: number; byteSize: number }) =>
    call<UploadTicket>(`/sessions/${token}/upload-url`, json('POST', c)),
  stats: (token: string) => call<Stats>(`/sessions/${token}/stats`),
  videoCheck: (token: string, videoIds: string[]) =>
    call<{ musicIds: string[]; checked: number; fromCache: number; fromApi: number; skipped: boolean }>(`/sessions/${token}/video-check`, json('POST', { videoIds })),
  recommendations: (token: string) => call<Recommendations>(`/sessions/${token}/recommendations`),
  remove: (token: string) => call<void>(`/sessions/${token}`, { method: 'DELETE' }),
}

/** 서명 URL로 S3에 직접 올린다. 받은 헤더 중 브라우저가 직접 정하는 Content-Length는 빼고 보낸다 */
export async function putToS3(ticket: UploadTicket, body: string): Promise<void> {
  const headers = Object.fromEntries(Object.entries(ticket.headers).filter(([k]) => k.toLowerCase() !== 'content-length'))
  let res: Response
  try {
    res = await fetch(ticket.url, { method: ticket.method, headers, body })
  } catch {
    throw new ApiError(0, 'UPLOAD_NETWORK', '파일을 올리지 못했습니다. 네트워크를 확인하고 다시 시도해 주세요.')
  }
  if (!res.ok) throw new ApiError(res.status, 'UPLOAD_FAILED', `파일을 올리지 못했습니다 (${res.status}).`)
}
