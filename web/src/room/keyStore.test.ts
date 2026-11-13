import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { keyStore } from './keyStore'

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
})
