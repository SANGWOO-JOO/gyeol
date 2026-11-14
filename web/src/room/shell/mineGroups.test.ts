import { describe, expect, it } from 'vitest'
import { groupHead, groupSongs } from './mineGroups'

const s = (id: number, group: object | null) => ({ id, group })
describe('내 노래 묶음', () => {
  it('같은 키는 쪽 경계를 넘어도 처음 묶음 아래로', () => {
    const page1 = [s(1, { artistId: 7, displayName: '윤하', count: 3 }), s(2, { artistId: 7, displayName: '윤하', count: 3 }), s(3, { artistId: 9, displayName: 'IU', count: 1 })]
    const page2 = [s(4, { artistId: 7, displayName: '윤하', count: 3 }), s(5, null)]
    const g = groupSongs('artist', [...page1, ...page2])
    expect(g.map(x => [x.head.title, x.items.map(i => i.id)])).toEqual([['윤하', [1, 2, 4]], ['IU', [3]], ['분류 중', [5]]])
    expect(g[0].head.count).toBe(3)
  })
  it('장르는 galaxyId로, 서버 key가 있으면 그것으로', () => {
    expect(groupHead('genre', { galaxyId: 3, name: '세련된 싱어송라이터 팝' })).toMatchObject({ key: 'genre:3', title: '세련된 싱어송라이터 팝' })
    expect(groupHead('genre', { key: 'g-x', name: 'a' }).key).toBe('g-x')
    expect(groupHead('genre', null)).toMatchObject({ key: 'genre:none', title: '분류 중' })
  })
})
