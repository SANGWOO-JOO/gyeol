// 방 열쇠를 이 기기에 둔다 (docs/todo/room-api-db.md 2절). 같은 값이 HttpOnly 쿠키로도 가지만,
// 카톡 안 브라우저처럼 쿠키가 잘 안 남는 곳을 위해 localStorage에도 두고 X-Room-Key 헤더로 보낸다.
// 저장 시각(savedAt)부터 180일이 지나면 읽을 때 버린다 (I-69, 처리방침 8절).
// 저장소가 막힌 환경(사생활 보호 창 등)에서는 조용히 실패하고, 쿠키만으로 동작한다.

export interface RoomKey {
  roomId: string
  key: string
  memberId: number
  nickname: string
  roomName: string
  savedAt: string
}

const STORE = 'gyeol.rooms.v1'
/** 기기에 둔 열쇠의 보관 기간 (I-69·I-70, 방 쿠키와 같은 180일). 마지막으로 방을 쓴 때(savedAt)부터 세고, 지나면 읽을 때 버린다 */
export const KEY_TTL_MS = 180 * 86_400_000

/** 저장 시각이 없는 옛 값은 지금 시각으로 채우고, 180일이 지난 값은 뺀다. 바뀐 게 있으면 changed */
export function prune(all: Record<string, RoomKey>, now: Date): { all: Record<string, RoomKey>; changed: boolean } {
  const out: Record<string, RoomKey> = {}
  let changed = false
  for (const [id, k] of Object.entries(all)) {
    if (!k || typeof k !== 'object') { changed = true; continue }
    const t = typeof k.savedAt === 'string' ? Date.parse(k.savedAt) : NaN
    if (Number.isNaN(t)) { out[id] = { ...k, savedAt: now.toISOString() }; changed = true; continue }
    if (now.getTime() - t > KEY_TTL_MS) { changed = true; continue }
    out[id] = k
  }
  return { all: out, changed }
}

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null
  }
}

function readAll(now: Date = new Date()): Record<string, RoomKey> {
  try {
    const raw = storage()?.getItem(STORE)
    const parsed: unknown = raw ? JSON.parse(raw) : {}
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    const { all, changed } = prune(parsed as Record<string, RoomKey>, now)
    if (changed) writeAll(all)
    return all
  } catch {
    return {}
  }
}

function writeAll(all: Record<string, RoomKey>) {
  try {
    storage()?.setItem(STORE, JSON.stringify(all))
  } catch {
    /* 저장 실패는 무시한다 (쿠키 열쇠는 남아 있음) */
  }
}

export const keyStore = {
  get(roomId: string, now: Date = new Date()): RoomKey | null {
    const k = readAll(now)[roomId]
    return k && typeof k.key === 'string' ? k : null
  },
  save(entry: Omit<RoomKey, 'savedAt'>, now: Date = new Date()) {
    const all = readAll(now)
    all[entry.roomId] = { ...entry, savedAt: now.toISOString() }
    writeAll(all)
  },
  /** 닉네임·방 이름이 바뀌었을 때 열쇠는 그대로 두고 표시용 값만 고친다 */
  update(roomId: string, patch: Partial<Pick<RoomKey, 'nickname' | 'roomName'>>) {
    const all = readAll()
    if (!all[roomId]) return
    all[roomId] = { ...all[roomId], ...patch }
    writeAll(all)
  },
  /** 방을 쓴 시각으로 savedAt만 바꾼다 (I-70: 내 정보를 받았을 때 부름). 열쇠가 없으면 아무것도 안 함 */
  touch(roomId: string, now: Date = new Date()) {
    const all = readAll(now)
    if (!all[roomId]) return
    all[roomId] = { ...all[roomId], savedAt: now.toISOString() }
    writeAll(all)
  },
  remove(roomId: string) {
    const all = readAll()
    if (!(roomId in all)) return
    delete all[roomId]
    writeAll(all)
  },
  /** 이 기기에서 들어간 방, 최근 순 ("내 방 찾기") */
  list(now: Date = new Date()): RoomKey[] {
    return Object.values(readAll(now))
      .filter(k => k && typeof k.key === 'string' && typeof k.roomId === 'string')
      .sort((a, b) => (b.savedAt ?? '').localeCompare(a.savedAt ?? ''))
  },
}
