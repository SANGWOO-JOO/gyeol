import { describe, expect, it } from 'vitest'
import { coOwners, memberTaste, pushRecent, sharedCount, topArtists } from './tabLogic'

const song = (trackId: number, memberIds: number[]) => ({ trackId, title: `t${trackId}`, coverUrl: null, memberIds })
const map = {
  galaxies: [{ id: 1, name: '청량 팝 걸그룹', x: 0, y: 0 }, { id: 2, name: '감성 발라드', x: 0, y: 0 }],
  planets: [
    { artistId: 10, name: 'IVE', x: 0, y: 0, galaxyId: 1, preparing: false, songs: [song(1, [1]), song(2, [1, 2]), song(3, [2])] },
    { artistId: 11, name: 'aespa', x: 0, y: 0, galaxyId: 1, preparing: false, songs: [song(4, [1])] },
    { artistId: 12, name: '윤하', x: 0, y: 0, galaxyId: 2, preparing: false, songs: [song(5, [2]), song(6, [2]), song(7, [1, 2])] },
    { artistId: 13, name: 'BoA', x: 0, y: 0, galaxyId: null, preparing: false, songs: [song(8, [1])] },
  ],
}

describe('판 탭 계산', () => {
  it('취향 한 줄: 많이 담은 구역 1개 + 가수 3명', () => {
    const t = memberTaste(map, 1)
    expect(t.galaxy).toEqual({ id: 1, name: '청량 팝 걸그룹' })
    expect(t.artists.map(a => [a.name, a.count])).toEqual([['IVE', 2], ['aespa', 1], ['BoA', 1]])
    expect(memberTaste(map, 2).galaxy?.name).toBe('감성 발라드')
    expect(memberTaste(map, 9)).toEqual({ galaxy: null, artists: [] })
  })
  it('나와 같이 n곡, 같이 담은 다른 사람', () => {
    expect(sharedCount(map, 1, 2)).toBe(2)
    expect(sharedCount(map, 1, 9)).toBe(0)
    expect(coOwners(map, 2, 1)).toEqual([2])
    expect(coOwners(map, 1, 1)).toEqual([])
  })
  it('우리 우주에 많은 가수: 곡 많은 순, 같으면 이름 순', () => {
    expect(topArtists(map, 3).map(a => a.name)).toEqual(['IVE', '윤하', 'aespa'])
  })
  it('최근 검색어: 맨 앞, 중복 하나(대소문자 무시), 최대 5', () => {
    expect(pushRecent(['윤하', 'IU'], 'iu')).toEqual(['iu', '윤하'])
    expect(pushRecent(['a', 'b', 'c', 'd', 'e'], 'f')).toEqual(['f', 'a', 'b', 'c', 'd'])
    expect(pushRecent(['a'], '  ')).toEqual(['a'])
  })
})
