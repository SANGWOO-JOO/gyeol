import { describe, expect, it } from 'vitest'
import { genreColor, genreFamily, photoCreditText, pillWidth, planetFace } from './faces'

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
  const song = (coverUrl: string | null, coverPath: string | null = null) => ({ trackId: 1, title: 't', coverUrl, coverPath, memberIds: [1] })
  const urls = (f: ReturnType<typeof planetFace>) => (f.kind === 'genre' ? 'genre' : `${f.kind}: ${f.sources.map(x => `${x.mode} ${x.url}`).join(', ')}`)
  it('사진(PHOTO) → 표지(COVER, 없으면 담긴 곡: 저장본 → 외부) → 장르색', () => {
    expect(urls(planetFace({ songs: [song('c.jpg')], face: { kind: 'PHOTO', url: '/api/rooms/r/artists/1/image' } })))
      .toBe('photo: cookie /api/rooms/r/artists/1/image, key /api/rooms/r/artists/1/image, plain c.jpg')
    expect(urls(planetFace({ songs: [song(null)], face: { kind: 'PHOTO', url: '/api/rooms/r/p' } }))).toBe('photo: cookie /api/rooms/r/p, key /api/rooms/r/p')
    // I-14: 서버 COVER는 이제 우리 주소, 곡의 저장본과 같으면 한 번만
    expect(urls(planetFace({ songs: [song('c.jpg', '/api/rooms/r/covers/1')], face: { kind: 'COVER', url: '/api/rooms/r/covers/1' } })))
      .toBe('cover: cookie /api/rooms/r/covers/1, key /api/rooms/r/covers/1, plain c.jpg')
    expect(urls(planetFace({ songs: [song('c.jpg')], face: { kind: 'COVER', url: 'x.jpg' } }))).toBe('cover: plain x.jpg, plain c.jpg')
    expect(urls(planetFace({ songs: [song(null), song('c.jpg')], face: { kind: 'NONE', url: null } }))).toBe('cover: plain c.jpg')
    expect(urls(planetFace({ songs: [song(null)], face: { kind: 'NONE', url: null } }))).toBe('genre')
    expect(urls(planetFace({ songs: [song('c.jpg')] }))).toBe('cover: plain c.jpg')
  })
  it('사진 출처 줄: 빈 칸은 빼고, 출처가 없으면 줄도 없음', () => {
    expect(photoCreditText({ author: '홍길동', license: 'CC BY-SA 4.0' })).toBe('사진: 홍길동 · CC BY-SA 4.0 · Wikimedia Commons')
    expect(photoCreditText({ author: '', license: 'CC0' })).toBe('사진: CC0 · Wikimedia Commons')
    expect(photoCreditText({ author: null, license: null })).toBe('사진: Wikimedia Commons')
    expect(photoCreditText({ author: 'A  b ', license: ' CC0' })).toBe('사진: A b · CC0 · Wikimedia Commons')
    expect(photoCreditText(null)).toBeNull()
  })
  it('알약 폭: 한글은 넓게, 로마자는 좁게', () => {
    expect(pillWidth('아이유', 11)).toBeGreaterThan(pillWidth('IU', 11))
    expect(pillWidth('', 11)).toBe(14)
  })
})
