import { describe, expect, it } from 'vitest'
import type { Planet, RoomMap, RoomSearchItem } from './roomApi'
import { addedMessage, codeMatchDisplay, failText, galaxyOfTrack, genreMixText, giftLine, giftResultText, imageQuota, unlockText, groupByGalaxy, mapCounts, names, planetPeople, searchBadge, songOwners, sourceName } from './roomLogic'

describe('코드 맞추기 표시 (8-b, 결정 "라")', () => {
  const m = { score: 42, confidence: 'LOW', minSongs: 6, sharedArtistCount: 2, sharedGalaxyCount: 1, sharedSongs: 0 }
  it('5~9곡이면 % 없이 겹친 것과 10곡 안내', () => {
    expect(codeMatchDisplay(m)).toEqual({
      showPercent: false, headline: '벌써 겹치는 게 있어요', note: '각자 10곡을 넘기면 코드 점수가 나와요',
      shared: ['같은 아티스트 2명', '같은 구역 1곳', '같이 담은 노래 0곡'],
    })
    expect(codeMatchDisplay({ ...m, sharedArtistCount: 0, sharedGalaxyCount: 0 }).headline).toBe('아직 겹치는 게 없어요')
  })
  it('10곡부터 %, 보통이면 20곡 안내, 높으면 안내 없음', () => {
    expect(codeMatchDisplay({ ...m, confidence: 'MEDIUM', score: 79 })).toMatchObject({ showPercent: true, headline: '코드가 79% 맞아요', note: '각자 20곡을 넘기면 더 정확해져요' })
    expect(codeMatchDisplay({ ...m, confidence: 'HIGH', score: 87 }).note).toBeNull()
  })
  it('선물 캐릭터 장르 문구 (4-k)', () => {
    expect(genreMixText([{ galaxy: '시티팝', songs: 25 }, { galaxy: '댄스 팝', songs: 15 }])).toBe('시티팝 25곡 · 댄스 팝 15곡')
    expect(genreMixText([])).toBeNull()
    expect(genreMixText(undefined)).toBeNull()
  })
  it('5곡 미만은 열리지 않음', () => {
    expect(unlockText(2)).toBe('3곡 더 담으면 열려요')
    expect(unlockText(5)).toBeNull()
  })
})

describe('선물 표시 (4-l)', () => {
  const base = { songCount: 47, nextMilestone: 50, remaining: 3, receivedToday: false, waiting: 0, readyBoxId: null }
  it('3곡 이하면 금빛 줄과 남은 곡 배지', () => {
    expect(giftLine(base)).toEqual({ text: '50곡 선물까지 3곡', gold: true, badge: '3', canOpen: false })
  })
  it('4곡 이상이면 보라 줄만', () => {
    expect(giftLine({ ...base, songCount: 40, remaining: 10 })).toMatchObject({ gold: false, badge: null })
  })
  it('오늘 받았으면 배지 없음', () => {
    expect(giftLine({ ...base, songCount: 64, nextMilestone: 100, remaining: 36, receivedToday: true }))
      .toEqual({ text: '오늘 선물은 받았어요 · 100곡까지 36곡', gold: false, badge: null, canOpen: false })
  })
  it('기다리는 상자가 있으면 "상자" 배지', () => {
    expect(giftLine({ ...base, receivedToday: true, waiting: 1 })).toMatchObject({ text: '기다리는 상자 1개 · 내일 열려요', badge: '상자' })
  })
  it('열 수 있는 상자가 가장 먼저', () => {
    expect(giftLine({ ...base, waiting: 1, readyBoxId: 7 })).toMatchObject({ canOpen: true, badge: '상자' })
  })
})

describe('가수 판', () => {
  const nameOf = (id: number) => ({ 1: '상우', 2: '민지', 3: '지훈' } as Record<number, string>)[id]
  it('같이 담은 노래는 금빛, 나를 앞에', () => {
    expect(songOwners([2, 1], 1, nameOf)).toEqual({ label: '나 · 민지 같이 담음', mine: true, gold: true })
    expect(songOwners([2, 3], 1, nameOf)).toEqual({ label: '민지 · 지훈 같이 담음', mine: false, gold: true })
  })
  it('혼자 담은 노래', () => {
    expect(songOwners([1], 1, nameOf)).toEqual({ label: '나', mine: true, gold: false })
    expect(songOwners([3], 1, nameOf)).toEqual({ label: '지훈', mine: false, gold: false })
  })
  it('출처 이름', () => {
    expect(sourceName('WIKIPEDIA')).toBe('위키백과')
    expect(sourceName('ETC')).toBe('ETC')
  })
})

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
  it('DB에 없는 곡도 따로 표시하지 않는다 (I-29)', () => {
    expect(searchBadge(item({}, null))).toEqual({ kind: 'none' })
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

  it('트랙의 구역 이름 (이름 없는 구역·구역 없는 가수는 빠짐)', () => {
    const g = galaxyOfTrack(m)
    expect(g.get(100)).toBe('새벽 감성 발라드')
    expect(g.has(200)).toBe(false)
    expect(g.has(300)).toBe(false)
  })
})

describe('새 그림 하루 10장 선착순 (⑯ 변경 11-14)', () => {
  it('남은 장수가 없으면(API 준비 전) 표시하지 않는다', () => {
    expect(imageQuota(undefined, 10)).toBeNull()
    expect(imageQuota(3, null)).toBeNull()
  })
  it('남은 장수와 선착순 안내, 0장이면 다 나감', () => {
    expect(imageQuota(3, 10)).toEqual({ text: '오늘 남은 새 그림 3/10장 · 선착순', soldOut: false })
    expect(imageQuota(0, 10)).toMatchObject({ soldOut: true, text: '오늘 남은 새 그림 0/10장 · 선착순' })
    expect(imageQuota(-1, 10)?.soldOut).toBe(true)
  })
  it('내 노래 선물 줄: 열 수 있는 상자가 있으면 남은 장수도 짧게', () => {
    const g = { songCount: 50, nextMilestone: 100, remaining: 50, receivedToday: false, waiting: 0, readyBoxId: 7 }
    expect(giftLine(g).text).toBe('선물 상자가 왔어요 · 눌러서 열어요')
    expect(giftLine({ ...g, imagesLeftToday: 3 }).text).toBe('선물 상자가 왔어요 · 오늘 새 그림 3장 남음')
    expect(giftLine({ ...g, imagesLeftToday: 0 }).text).toBe('선물 상자가 왔어요 · 오늘 새 그림은 다 나갔어요')
    expect(giftLine({ ...g, readyBoxId: null, imagesLeftToday: 3 }).text).toBe('100곡 선물까지 50곡')
  })
  it('4-k 결과: 기본 캐릭터 이유에 따라 사실대로', () => {
    expect(giftResultText(true, 'DAILY_LIMIT').text).toBe('방금 오늘 몫이 다 떨어져 기본 캐릭터를 보냈어요.')
    expect(giftResultText(true, 'FAILED').text).toBe('오늘은 새 그림을 만들 수 없어서 미리 준비한 캐릭터를 드려요.')
    expect(giftResultText(true, undefined).text).toBe('오늘은 새 그림을 만들 수 없어서 미리 준비한 캐릭터를 드려요.')
    expect(giftResultText(false, 'DAILY_LIMIT').text).toBeNull()
  })
})
