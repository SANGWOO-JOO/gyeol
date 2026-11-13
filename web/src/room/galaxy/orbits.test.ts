import { describe, expect, it } from 'vitest'
import { orbitLayout } from './orbits'

const song = (trackId: number, ...memberIds: number[]) => ({ trackId, title: '', coverUrl: null, memberIds })

describe('궤도 순서 (규칙 ⑫)', () => {
  it('같이 담은 노래가 가장 안쪽 금빛, 사람 궤도는 곡 수가 적은 사람부터', () => {
    const rings = orbitLayout({ artistId: 1, songs: [song(1, 10), song(2, 10), song(3, 10), song(4, 20), song(5, 10, 20)] }, [10, 20])
    expect(rings.map(r => r.kind)).toEqual(['gold', 'person', 'person'])
    expect(rings[0].sats.map(s => s.trackId)).toEqual([5])
    expect(rings[1].memberIds).toEqual([20]) // 20은 2곡, 10은 4곡
    expect(rings[2].memberIds).toEqual([10])
  })
  it('곡 수가 같으면 먼저 들어온 사람이 안쪽', () => {
    const rings = orbitLayout({ artistId: 1, songs: [song(1, 30), song(2, 10)] }, [10, 30])
    expect(rings.map(r => r.memberIds[0])).toEqual([10, 30])
  })
  it('같이 담은 노래만 있으면 사람 궤도 없음', () => {
    expect(orbitLayout({ artistId: 1, songs: [song(1, 1, 2)] }, [1, 2]).map(r => r.kind)).toEqual(['gold'])
  })
  it('같이 담은 노래가 8곡 이상이면 금빛 두 겹, 담은 사람 많은 노래가 안쪽', () => {
    const songs = Array.from({ length: 8 }, (_, i) => song(i + 1, 1, 2))
    songs.push(song(99, 1, 2, 3))
    const rings = orbitLayout({ artistId: 1, songs }, [1, 2, 3])
    expect(rings.map(r => r.kind)).toEqual(['gold', 'gold'])
    expect(rings[0].sats[0].trackId).toBe(99)
  })
  it('사람 궤도는 지도에서 최대 3겹: 남는 사람은 가장 바깥에 섞는다', () => {
    const rings = orbitLayout({ artistId: 1, songs: [song(1, 1), song(2, 2), song(3, 3), song(4, 4), song(5, 5)] }, [1, 2, 3, 4, 5])
    expect(rings).toHaveLength(3)
    expect(rings[2].memberIds).toEqual([3, 4, 5])
    expect(rings[2].sats).toHaveLength(3)
  })
  it('같은 데이터면 위성 각도도 같다', () => {
    const p = { artistId: 7, songs: [song(1, 1), song(2, 1)] }
    expect(orbitLayout(p, [1])).toEqual(orbitLayout(p, [1]))
  })
})

describe('궤도 간격도 확대 체감을 따른다 (I-37)', () => {
  it('grow를 주면 간격이 그만큼 넓어진다', async () => {
    const { ringRadius, RING_GAP } = await import('./orbits')
    expect(ringRadius(10, 0)).toBe(10 + RING_GAP)
    expect(ringRadius(10, 1, 2)).toBe(10 + RING_GAP * 2 * 2)
  })
})
