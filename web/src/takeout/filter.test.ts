import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { filterTakeout, TakeoutFormatError, topVideoIds, uploadBody, watchedTitle } from './filter'

const fixture = (name: string) =>
  JSON.parse(readFileSync(fileURLToPath(new URL(`../../../tools/fixtures/${name}`, import.meta.url)), 'utf8'))

const watch = (title: string, channel: string | null, extra: object = {}) => ({
  header: 'YouTube',
  title: `${title} 을(를) 시청했습니다.`,
  titleUrl: 'https://www.youtube.com/watch?v=abcdefghijk',
  ...(channel ? { subtitles: [{ name: channel, url: 'https://www.youtube.com/channel/x' }] } : {}),
  time: '2026-10-01T00:00:00.000Z',
  ...extra,
})

describe('watchedTitle', () => {
  it('한국어·영어 시청 문구를 떼어 낸다', () => {
    expect(watchedTitle('좋은 날 을(를) 시청했습니다.')).toBe('좋은 날')
    expect(watchedTitle('Watched Yellow')).toBe('Yellow')
    expect(watchedTitle('좋은 날 을(를) 검색했습니다.')).toBeNull()
    expect(watchedTitle('가상항목 목록을 확인함')).toBeNull()
  })
})

describe('filterTakeout', () => {
  it('내 활동(한국어) 파일: 시청만 남기고 음악만 보낸다', () => {
    const data = fixture('myactivity-small.json')
    const r = filterTakeout(data)
    expect(r.total + r.otherActivity).toBe(data.length)
    expect(r.music + r.unclassified + r.removed).toBe(r.total)
    expect(r.music).toBeGreaterThan(0)
    expect(r.items.every(i => i.source === 'MUSIC_APP' || i.channel!.endsWith(' - Topic'))).toBe(true)
    // 검색어·일반 영상 제목은 보내는 파일에 없다
    const body = uploadBody(r.items)
    expect(body).not.toContain('검색')
    expect(body).not.toContain('가상항목')
    expect(r.periodStart! <= r.periodEnd!).toBe(true)
  })

  it('YouTube 및 YouTube Music(영어) 파일도 같은 규칙으로 읽는다', () => {
    const r = filterTakeout(fixture('watch-history-en-small.json'))
    expect(r.music).toBeGreaterThan(0)
    expect(r.items.some(i => i.title.startsWith('Watched'))).toBe(false)
    expect(uploadBody(r.items)).not.toContain('일반 영상')
  })

  it('삭제된 영상과 일반 영상은 보내지 않는다', () => {
    const r = filterTakeout([
      watch('곡', '가수 - Topic'),
      watch('https://www.youtube.com/watch?v=abcdefghijk', null),
      watch('브이로그', '어떤 채널'),
      watch('곡2', '가수', { header: 'YouTube Music', titleUrl: 'https://music.youtube.com/watch?v=zzzzzzzzzzz' }),
    ])
    expect(r).toMatchObject({ total: 4, music: 2, removed: 1, unclassified: 1 })
    expect(r.items.map(i => [i.title, i.source, i.videoId])).toEqual([
      ['곡', 'YOUTUBE', 'abcdefghijk'],
      ['곡2', 'MUSIC_APP', 'zzzzzzzzzzz'],
    ])
  })

  it('검색 기록만 있는 파일은 거절한다', () => {
    const search = [{ header: 'YouTube', title: '노래 을(를) 검색했습니다.', time: '2026-10-01T00:00:00Z' }]
    expect(() => filterTakeout(search)).toThrow(/검색 기록 파일/)
  })

  it('YouTube 기록이 아닌 JSON은 거절한다', () => {
    expect(() => filterTakeout({ a: 1 })).toThrow(TakeoutFormatError)
    expect(() => filterTakeout([{ foo: 'bar' }])).toThrow(TakeoutFormatError)
  })

  it('미분류 시청은 브라우저 안에 두고, 재생 순 상위 영상 ID만 판정에 보낸다 (L2-3b)', () => {
    const r = filterTakeout([
      watch('브이로그', '어떤 채널', { titleUrl: 'https://www.youtube.com/watch?v=aaaaaaaaaaa' }),
      watch('[MV] 밤편지', '1theK', { titleUrl: 'https://www.youtube.com/watch?v=bbbbbbbbbbb' }),
      watch('[MV] 밤편지', '1theK', { titleUrl: 'https://www.youtube.com/watch?v=bbbbbbbbbbb' }),
      watch('곡', '가수 - Topic'),
    ])
    expect(r.unclassifiedItems.map(i => i.title)).toEqual(['브이로그', '[MV] 밤편지', '[MV] 밤편지'])
    expect(uploadBody(r.items)).not.toContain('브이로그') // 판정 전에는 올리지 않는다
    expect(topVideoIds(r.unclassifiedItems)).toEqual(['bbbbbbbbbbb', 'aaaaaaaaaaa'])
    expect(topVideoIds(r.unclassifiedItems, 1)).toEqual(['bbbbbbbbbbb'])
  })
})
