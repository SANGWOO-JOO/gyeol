import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { KEY_TTL_MS, keyStore, prune } from './keyStore'

/** node 환경용 간단한 localStorage */
class MemoryStorage {
  data = new Map<string, string>()
  getItem(k: string) { return this.data.get(k) ?? null }
  setItem(k: string, v: string) { this.data.set(k, v) }
  removeItem(k: string) { this.data.delete(k) }
  clear() { this.data.clear() }
}

const entry = (roomId: string) => ({ roomId, key: `key-${roomId}`, memberId: 1, nickname: '상우', roomName: `${roomId} 우주` })

describe('keyStore', () => {
  let store: MemoryStorage
  beforeEach(() => {
    store = new MemoryStorage()
    Object.defineProperty(globalThis, 'localStorage', { value: store, configurable: true })
  })
  afterEach(() => { delete (globalThis as { localStorage?: unknown }).localStorage })

  it('방마다 열쇠를 따로 저장하고 꺼낸다', () => {
    keyStore.save(entry('a'))
    keyStore.save(entry('b'))
    expect(keyStore.get('a')?.key).toBe('key-a')
    expect(keyStore.get('b')?.key).toBe('key-b')
    expect(keyStore.get('c')).toBeNull()
  })

  it('목록은 최근에 들어간 방부터', () => {
    keyStore.save(entry('old'), new Date('2026-11-01T00:00:00Z'))
    keyStore.save(entry('new'), new Date('2026-11-13T00:00:00Z'))
    expect(keyStore.list().map(k => k.roomId)).toEqual(['new', 'old'])
  })

  it('지우면 그 방만 사라지고, 표시 값만 고칠 수 있다', () => {
    keyStore.save(entry('a'))
    keyStore.save(entry('b'))
    keyStore.remove('a')
    keyStore.update('b', { nickname: '민지' })
    expect(keyStore.get('a')).toBeNull()
    expect(keyStore.get('b')).toMatchObject({ key: 'key-b', nickname: '민지' })
  })

  it('저장 값이 깨져 있으면 빈 목록으로 본다', () => {
    store.setItem('gyeol.rooms.v1', '{not json')
    expect(keyStore.list()).toEqual([])
    expect(keyStore.get('a')).toBeNull()
    keyStore.save(entry('a'))
    expect(keyStore.get('a')?.key).toBe('key-a')
  })

  it('저장소가 막혀 있어도 예외를 던지지 않는다', () => {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      get() { throw new Error('SecurityError') },
    })
    expect(() => keyStore.save(entry('a'))).not.toThrow()
    expect(keyStore.get('a')).toBeNull()
    expect(keyStore.list()).toEqual([])
  })

  describe('180일 보관 (I-69)', () => {
    const t0 = new Date('2026-01-01T00:00:00Z')
    const at = (ms: number) => new Date(t0.getTime() + ms)

    it('180일이 지난 열쇠는 읽을 때 버리고 저장소에서도 지운다', () => {
      keyStore.save(entry('a'), t0)
      expect(keyStore.get('a', at(KEY_TTL_MS))?.key).toBe('key-a')
      expect(keyStore.get('a', at(KEY_TTL_MS + 1))).toBeNull()
      expect(JSON.parse(store.getItem('gyeol.rooms.v1')!)).toEqual({})
    })

    it('목록에서도 만료된 방은 빠진다', () => {
      keyStore.save(entry('old'), t0)
      keyStore.save(entry('new'), at(KEY_TTL_MS))
      expect(keyStore.list(at(KEY_TTL_MS + 1)).map(k => k.roomId)).toEqual(['new'])
    })

    it('저장 시각이 없는 옛 값은 처음 읽을 때 지금 시각으로 채운다', () => {
      store.setItem('gyeol.rooms.v1', JSON.stringify({ a: entry('a') }))
      expect(keyStore.get('a', t0)?.savedAt).toBe(t0.toISOString())
      expect(JSON.parse(store.getItem('gyeol.rooms.v1')!).a.savedAt).toBe(t0.toISOString())
      expect(keyStore.get('a', at(KEY_TTL_MS))?.key).toBe('key-a')
    })

    it('이름만 바꿔도 저장 시각은 그대로', () => {
      const now = new Date()
      keyStore.save(entry('a'), now)
      keyStore.update('a', { nickname: '새 이름' })
      expect(keyStore.get('a')).toMatchObject({ nickname: '새 이름', savedAt: now.toISOString() })
    })

    it('prune: 잘못된 값은 빼고 바뀐 게 없으면 changed=false', () => {
      const ok = { ...entry('a'), savedAt: t0.toISOString() }
      expect(prune({ a: ok }, t0)).toEqual({ all: { a: ok }, changed: false })
      expect(prune({ a: ok, b: null as never }, t0).changed).toBe(true)
    })

    it('touch: 방을 쓰면 180일을 다시 센다', () => {
      keyStore.save(entry('a'), t0)
      keyStore.touch('a', at(KEY_TTL_MS - 1))
      expect(keyStore.get('a', at(KEY_TTL_MS + 1))?.savedAt).toBe(at(KEY_TTL_MS - 1).toISOString())
      expect(keyStore.get('a', at(2 * KEY_TTL_MS))).toBeNull()
    })

    it('touch: 없는 방이나 이미 만료된 방은 되살리지 않는다', () => {
      keyStore.touch('none', t0)
      expect(store.getItem('gyeol.rooms.v1')).toBeNull()
      keyStore.save(entry('a'), t0)
      keyStore.touch('a', at(KEY_TTL_MS + 1))
      expect(keyStore.get('a', at(KEY_TTL_MS + 1))).toBeNull()
    })
  })
})
