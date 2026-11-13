import { describe, expect, it } from 'vitest'
import type { Planet, RoomMap, RoomSearchItem } from './roomApi'
import { addedMessage, failText, groupByGalaxy, mapCounts, names, planetPeople, searchBadge } from './roomLogic'

const item = (over: Partial<RoomSearchItem> = {}, trackId: number | null = 1): RoomSearchItem => ({
  item: { title: '밤편지', artist: '아이유', mbid: null, listeners: null, releaseYear: 2017, trackId, coverUrl: null },
  mine: false, sameSong: [], sameArtist: [], ...over,
})

const planet = (artistId: number, galaxyId: number | null, songs: number[][]): Planet => ({
  artistId, name: `가수${artistId}`, x: 0, y: 0, galaxyId, preparing: false,
  songs: songs.map((memberIds, i) => ({ trackId: artistId * 100 + i, title: `곡${i}`, coverUrl: null, memberIds })),
})

const map = (planets: Planet[]): RoomMap => ({
  rev: 1, placeholder: false, members: [], planets,
  galaxies: [{ id: 10, name: '새벽 감성 발라드', x: 0, y: 0 }, { id: 20, name: null, x: 0, y: 0 }], ghosts: [],
})

describe('searchBadge', () => {
  it('이미 담음이 가장 먼저', () => {
    expect(searchBadge(item({ mine: true, sameSong: ['민지'] }))).toEqual({ kind: 'mine' })
  })
  it('친구가 담은 노래는 금빛 나도 담기', () => {
    expect(searchBadge(item({ sameSong: ['민지'], sameArtist: ['지훈'] }))).toEqual({ kind: 'sameSong', text: '민지님이 담은 노래' })
  })
  it('같은 가수의 다른 노래', () => {
    expect(searchBadge(item({ sameArtist: ['민지', '지훈'] }))).toEqual({ kind: 'sameArtist', text: '민지님·지훈님과 같은 아티스트' })
  })
  it('DB에 없는 곡은 찾아서 담는다', () => {
    expect(searchBadge(item({}, null)).kind).toBe('identify')
    expect(searchBadge(item()).kind).toBe('none')
  })
})

describe('names', () => {
  it('셋부터는 줄인다', () => {
    expect(names(['민지'])).toBe('민지님')
    expect(names(['민지', '지훈', 'Sam'])).toBe('민지님 외 2명')
  })
})

describe('addedMessage', () => {
  it('혼자면 가수 곁에 떴다, 둘 이상이면 금빛 겹침', () => {
    expect(addedMessage({ artist: '아이유', sharedCount: 1 })).toEqual({ title: '아이유 곁에 내 노래가 떴어요', gold: false })
    expect(addedMessage({ artist: '아이유', sharedCount: 2 }, ['민지'])).toEqual({ title: '민지님과 같은 노래를 골랐어요', gold: true })
    expect(addedMessage({ artist: '아이유', sharedCount: 3 }).title).toBe('3명이 같은 노래를 골랐어요')
  })
})

describe('failText', () => {
  it('모르는 이유는 다른 이름으로 찾아보라고 안내', () => {
    expect(failText('NOT_FOUND')).toContain('영어 제목')
    expect(failText(null)).toContain('영어 제목')
    expect(failText('REJECTED')).toContain('메들리')
  })
})

describe('지도 집계', () => {
  const m = map([planet(1, 10, [[1], [1, 2]]), planet(2, 20, [[2]]), planet(3, null, [[1]]), planet(4, 99, [[3]])])

  it('노래·가수 수', () => {
    expect(mapCounts(m)).toEqual({ songs: 5, artists: 4 })
    expect(mapCounts(null)).toEqual({ songs: 0, artists: 0 })
  })

  it('구역별로 묶고, 구역 없는 가수는 맨 뒤', () => {
    const g = groupByGalaxy(m)
    expect(g.map(x => x.name)).toEqual(['새벽 감성 발라드', '이름 짓는 중인 구역', '자리를 찾는 중'])
    expect(g[2].planets.map(p => p.artistId).sort()).toEqual([3, 4])
  })

  it('행성의 사람과 같이 담은 노래', () => {
    expect(planetPeople(m.planets[0])).toEqual({ memberIds: [1, 2], shared: 1 })
  })
})
