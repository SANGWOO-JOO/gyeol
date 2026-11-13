// 방 열쇠를 이 기기에 둔다 (docs/todo/room-api-db.md 2절). 같은 값이 HttpOnly 쿠키로도 가지만,
// 카톡 안 브라우저처럼 쿠키가 잘 안 남는 곳을 위해 localStorage에도 두고 X-Room-Key 헤더로 보낸다.
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

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null
  }
}

function readAll(): Record<string, RoomKey> {
  try {
    const raw = storage()?.getItem(STORE)
    const parsed: unknown = raw ? JSON.parse(raw) : {}
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, RoomKey>) : {}
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
  get(roomId: string): RoomKey | null {
    const k = readAll()[roomId]
    return k && typeof k.key === 'string' ? k : null
  },
  save(entry: Omit<RoomKey, 'savedAt'>, now: Date = new Date()) {
    const all = readAll()
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
  remove(roomId: string) {
    const all = readAll()
    if (!(roomId in all)) return
    delete all[roomId]
    writeAll(all)
  },
  /** 이 기기에서 들어간 방, 최근 순 ("내 방 찾기") */
  list(): RoomKey[] {
    return Object.values(readAll())
      .filter(k => k && typeof k.key === 'string' && typeof k.roomId === 'string')
      .sort((a, b) => (b.savedAt ?? '').localeCompare(a.savedAt ?? ''))
  },
}
