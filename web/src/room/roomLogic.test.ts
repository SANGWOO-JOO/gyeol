import { describe, expect, it } from 'vitest'
import type { Planet, RoomMap, RoomSearchItem } from './roomApi'
import { searchable, searchDelay, addedMessage, appendSearch, artistDisplayName, artistFaceUrl, isNewArtist, showArtistMore, appendSongs, artistCardSub, asSearchItem, canLoadMore, artistEndText, groupByRelease, mergeArtist, artistSongsErrorText, releaseText, scanNotice, scanPollMs, sharedWithText, trackKey, codeMatchDisplay, failText, galaxyOfTrack, genreMixText, giftLine, giftResultText, imageQuota, unlockText, groupByGalaxy, mapCounts, names, planetPeople, searchBadge, songOwners, sourceName } from './roomLogic'

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

describe('I-52 처음 보는 가수', () => {
  const map = { planets: [
    { artistId: 1, name: '실리카겔', x: 0, y: 0, galaxyId: null, preparing: true, songs: [{ trackId: 10, title: 'NO PAIN', coverUrl: null, memberIds: [1] }] },
    { artistId: 2, name: '아이유', x: 0, y: 0, galaxyId: 3, preparing: false, songs: [{ trackId: 20, title: '밤편지', coverUrl: null, memberIds: [1] }] },
  ] }
  it('담은 곡의 행성이 준비 중이면 처음 보는 가수', () => {
    expect(isNewArtist(map, 10)).toBe(true)
    expect(isNewArtist(map, 20)).toBe(false)
  })
  it('지도를 못 받았거나 곡이 아직 지도에 없으면 일반 알림', () => {
    expect(isNewArtist(null, 10)).toBe(false)
    expect(isNewArtist(map, 99)).toBe(false)
  })
  it('문구: 4-g 표현, 같이 담은 노래(금빛)가 먼저', () => {
    expect(addedMessage({ artist: 'Silica Gel', title: 'NO PAIN', sharedCount: 1 }, [], true))
      .toEqual({ title: '처음 보는 아티스트예요', gold: false, sub: 'Silica Gel의 자리를 우주에 만드는 중이에요 · NO PAIN' })
    expect(addedMessage({ artist: 'Silica Gel', sharedCount: 2 }, ['민지'], true).gold).toBe(true)
    expect(addedMessage({ artist: '아이유', sharedCount: 1 }, [], false).sub).toBeUndefined()
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

describe('I-30 가수 화면', () => {
  const t = (title: string, trackId: number | null = null) => ({ title, artist: '다비치', mbid: null, listeners: null, releaseYear: null, trackId, coverUrl: null })
  const song = (title: string, releaseDate: string | null = null, trackId: number | null = null) => ({ item: t(title, trackId), mine: false, sameSong: [], releaseDate })

  it('가수 카드 둘째 줄: 곡 수가 오면 곡 수, 없으면 지도 여부, 둘 다 없으면 빈 줄', () => {
    expect(artistCardSub({ artistId: 1, name: '다비치', songCountInRoom: 3 })).toBe('우리 우주에 3곡')
    expect(artistCardSub({ artistId: 1, name: '다비치', songCountInRoom: 0 })).toBe('아직 우리 우주에 담은 노래가 없어요')
    expect(artistCardSub({ artistId: 1, name: '다비치', onMap: true })).toBe('우리 지도에 있는 가수예요')
    expect(artistCardSub({ artistId: null, name: '다비치' })).toBe('')
  })
  it('인기순은 5페이지(100곡)까지만 이어 불러온다 (A6)', () => {
    expect(canLoadMore({ hasMore: true, page: 1 })).toBe(true)
    expect(canLoadMore({ hasMore: true, page: 5 })).toBe(false)
    expect(canLoadMore({ hasMore: false, page: 2 })).toBe(false)
  })
  it('끝 문구: 100곡 상한이면 "여기까지", 실제로 끝났으면 "다 봤어요" (I-42 B)', () => {
    expect(artistEndText({ hasMore: false, page: 5 }, 100)).toContain('여기까지')
    expect(artistEndText({ hasMore: true, page: 5 }, 97)).toContain('여기까지')
    expect(artistEndText({ hasMore: false, page: 5 }, 87)).toBe('이 가수의 노래를 다 봤어요')
    expect(artistEndText({ hasMore: false, page: 2 }, 31)).toBe('이 가수의 노래를 다 봤어요')
  })
  it('이어 붙일 때 같은 곡은 앞의 것만 남긴다', () => {
    const a = [song('8282', null, 1), song('My Man')]
    const b = [song('8282', null, 1), song('my man'), song('시간아 멈춰라')]
    expect(appendSongs(a, b).map(x => x.item.title)).toEqual(['8282', 'My Man', '시간아 멈춰라'])
  })
  it('방 검색 모양으로 바꾸면 담기·금빛 표시를 그대로 쓴다', () => {
    const r = asSearchItem({ ...song('8282'), sameSong: ['민지'] })
    expect(r.sameArtist).toEqual([])
    expect(searchBadge(r)).toEqual({ kind: 'sameSong', text: '민지님이 담은 노래' })
    expect(trackKey(t('A', 7))).toBe('t7')
    expect(trackKey(t('My Man'))).toBe('qmy man|다비치')
  })
  it('가수 머리: 응답에 없는 곡 수는 카드 것을 남긴다', () => {
    const card = { artistId: 2, name: 'DAVICHI', songCountInRoom: 3, onMap: true }
    expect(mergeArtist(card, { artistId: 2, name: 'DAVICHI', aliases: ['다비치'] })).toEqual({ ...card, aliases: ['다비치'] })
    expect(mergeArtist(card, { artistId: 2, name: '다비치', songCountInRoom: 5 }).songCountInRoom).toBe(5)
    expect(mergeArtist(card, null)).toBe(card)
  })
  it('가수 화면 오류 문구', () => {
    expect(artistSongsErrorText(503, 'ARTIST_SONGS_UNAVAILABLE', 'x')).toContain('잠시 뒤 다시 시도')
    expect(artistSongsErrorText(404, 'ARTIST_NOT_FOUND', 'x')).toBe('이 가수를 찾지 못했어요')
    expect(artistSongsErrorText(501, 'LATEST_NOT_READY', 'x')).toContain('최신순은 준비 중')
    expect(artistSongsErrorText(0, 'NETWORK', '서버에 연결하지 못했어요')).toBe('서버에 연결하지 못했어요')
    expect(artistSongsErrorText(500, 'UNKNOWN', '서버 오류')).toBe('노래를 불러오지 못했어요')
  })
  it('내가 담은 곡을 친구도 담았으면 같이 담음', () => {
    expect(sharedWithText(['민지'])).toBe('민지님과 같이 담음')
    expect(sharedWithText(['민지', '지훈'])).toBe('민지님·지훈님과 같이 담음')
    expect(sharedWithText([])).toBeNull()
  })
  it('발매일 표시', () => {
    expect(releaseText('2021-03-05')).toBe('2021.03.05')
    expect(releaseText('2021-03')).toBe('2021.03')
    expect(releaseText('2021')).toBe('2021')
    expect(releaseText(null)).toBeNull()
    expect(releaseText('언젠가')).toBeNull()
  })
  it('최신순은 연도별로 묶고 날짜 없는 곡은 맨 뒤 "발매일 모름"', () => {
    const g = groupByRelease([song('a', '2022-01-02'), song('x', null), song('b', '2021-05'), song('c', '2021'), song('y', '')])
    expect(g.map(x => [x.label, x.unknown, x.items.map(i => i.item.title).join('')])).toEqual([
      ['2022', false, 'a'], ['2021', false, 'bc'], ['발매일 모름', true, 'xy'],
    ])
    expect(groupByRelease([])).toEqual([])
  })
  it('수집 중 다시 읽기는 처음 빨리, 오래 걸리면 4초', () => {
    expect([0, 1, 2, 3, 9].map(scanPollMs)).toEqual([1200, 2000, 2000, 4000, 4000])
  })
  it('최신순 수집 안내', () => {
    expect(scanNotice('RUNNING')).toMatchObject({ title: '발매일을 모으는 중이에요', busy: true })
    expect(scanNotice('QUEUED')?.busy).toBe(true)
    expect(scanNotice('FAILED')).toMatchObject({ busy: false })
    expect(scanNotice('DONE')).toBeNull()
    expect(scanNotice(undefined)).toBeNull()
  })
})

describe('검색 목록 끝 가수 버튼', () => {
  it('가수 카드가 있고 결과가 있을 때만', () => {
    expect(showArtistMore({ name: '윤하' }, [1], false)).toBe(true)
    expect(showArtistMore(null, [1], false)).toBe(false)
    expect(showArtistMore({ name: '윤하' }, [], false)).toBe(false)
    expect(showArtistMore({ name: '윤하' }, [1], true)).toBe(false)
  })
})

describe('검색 더보기 이어 붙이기', () => {
  const it2 = (trackId: number | null, title: string, artist = '윤하') => ({ item: { trackId, title, artist } as never, mine: false, sameSong: [], sameArtist: [] })
  it('이미 보인 곡(trackId, 없으면 제목|가수)과 다음 쪽 안의 중복은 빼고 순서대로 붙인다', () => {
    const prev = [it2(1, '비밀번호 486'), it2(null, '기다리다')]
    const next = [it2(1, '비밀번호 486'), it2(2, '사건의 지평선'), it2(null, '기다리다'), it2(2, '사건의 지평선'), it2(3, '오늘 헤어졌어요')]
    expect(appendSearch(prev, next).map(x => (x.item as { title: string }).title)).toEqual(['비밀번호 486', '기다리다', '사건의 지평선', '오늘 헤어졌어요'])
  })
  it('다음 쪽이 비면 그대로', () => {
    const prev = [it2(1, 'a')]
    expect(appendSearch(prev, [])).toEqual(prev)
  })
})

describe('검색 가수 카드 얼굴·이름', () => {
  it('PHOTO·COVER는 그 주소, NONE·없음은 null(이니셜)', () => {
    expect(artistFaceUrl({ face: { kind: 'PHOTO', url: '/api/rooms/r/artists/7/image' } })).toBe('/api/rooms/r/artists/7/image')
    expect(artistFaceUrl({ face: { kind: 'COVER', url: '/api/rooms/r/covers/3' } })).toBe('/api/rooms/r/covers/3')
    expect(artistFaceUrl({ face: { kind: 'NONE', url: null } })).toBeNull()
    expect(artistFaceUrl({})).toBeNull()
  })
  it('이름은 displayName, 없으면 MusicBrainz 이름', () => {
    expect(artistDisplayName({ name: 'YOUNHA', displayName: '윤하' })).toBe('윤하')
    expect(artistDisplayName({ name: 'ZARD', displayName: null })).toBe('ZARD')
  })
})

describe('검색 글자 수', () => {
  it('한 글자는 한글 완성 글자·한자·가나만', () => {
    expect(searchable('비')).toBe(true)
    expect(searchable('風')).toBe(true)
    expect(searchable('あ')).toBe(true)
    expect(searchable('ア')).toBe(true)
    expect(searchable('ㅂ')).toBe(false)
    expect(searchable('a')).toBe(false)
    expect(searchable('7')).toBe(false)
    expect(searchable(' ')).toBe(false)
  })
  it('두 글자부터는 무엇이든, 한 글자는 400ms', () => {
    expect(searchable('ab')).toBe(true)
    expect(searchable(' 아이 ')).toBe(true)
    expect(searchDelay('비')).toBe(400)
    expect(searchDelay('아이유')).toBe(300)
  })
})
