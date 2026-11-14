import { describe, expect, it } from 'vitest'
import { genreColor, genreFamily, pillWidth, planetFace } from './faces'

describe('I-44 장르 계열 색', () => {
  it('구역 이름의 낱말로 계열을 고른다 (먼저 맞는 계열)', () => {
    expect(genreFamily('강렬 드릴 래프').key).toBe('hiphop')
    expect(genreFamily('감성 발라드 록').key).toBe('ballad')
    expect(genreFamily('영혼 깊은 컨템퍼러리 R&B').key).toBe('soul')
    expect(genreFamily('전자음악 보컬로이드 팝').key).toBe('electronic')
    expect(genreFamily('무거운 얼터너티브 메탈').key).toBe('heavy')
    expect(genreFamily('90년대 얼터너티브 록').key).toBe('rock')
    expect(genreFamily('감성 인디 포크').key).toBe('indie')
    expect(genreFamily('청량 팝 걸그룹').key).toBe('dance')
    expect(genreFamily('세련된 싱어송라이터 팝').key).toBe('indie')
    expect(genreFamily('복고 팝 감성').key).toBe('pop')
    expect(genreFamily('정보 부족 구역').key).toBe('other')
    expect(genreFamily(null).key).toBe('other')
  })
  it('같은 계열은 같은 색상, 구역 id로 밝기만 다르다', () => {
    expect(genreColor('청량 팝 걸그룹', 100)).toMatch(/^hsl\(332 /)
    expect(genreColor('현대 팝 걸그룹', 39)).toMatch(/^hsl\(332 /)
    const shades = new Set([0, 1, 2].map(i => genreColor('감성 발라드', i)))
    expect(shades.size).toBe(3)
    expect(genreColor('감성 발라드', 3)).toBe(genreColor('감성 발라드', 0))
  })
})

describe('I-44 얼굴 고르기', () => {
  const song = (coverUrl: string | null) => ({ trackId: 1, title: 't', coverUrl, memberIds: [1] })
  it('서버 얼굴 → 담긴 곡 표지 → 장르색', () => {
    expect(planetFace({ songs: [song('c.jpg')], face: { kind: 'PHOTO', url: 'p.jpg' } })).toEqual({ kind: 'photo', url: 'p.jpg' })
    expect(planetFace({ songs: [song('c.jpg')], face: { kind: 'COVER', url: 'x.jpg' } })).toEqual({ kind: 'cover', url: 'x.jpg' })
    expect(planetFace({ songs: [song(null), song('c.jpg')], face: null })).toEqual({ kind: 'cover', url: 'c.jpg' })
    expect(planetFace({ songs: [song(null)] })).toEqual({ kind: 'genre' })
    expect(planetFace({ songs: [song('c.jpg')], face: { kind: 'PHOTO', url: null } })).toEqual({ kind: 'cover', url: 'c.jpg' })
  })
  it('알약 폭: 한글은 넓게, 로마자는 좁게', () => {
    expect(pillWidth('아이유', 11)).toBeGreaterThan(pillWidth('IU', 11))
    expect(pillWidth('', 11)).toBe(14)
  })
})
