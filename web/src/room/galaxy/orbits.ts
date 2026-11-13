import type { Planet } from '../roomApi'

/**
 * 행성 둘레 궤도 배치 (room-plan.md 규칙 ⑫).
 * - 가장 안쪽 = 같이 담은 노래(금빛). 8곡 이상이면 금빛 궤도를 한 겹 더(항상 사람 궤도보다 안쪽).
 * - 그 바깥 = 사람별 궤도. 이 행성에 담은 곡 수가 적은 사람부터 안쪽, 같으면 먼저 들어온 사람(members 순서)이 안쪽.
 * - 지도는 사람 궤도 최대 3겹: 4~5명이 담았으면 남는 사람은 가장 바깥 궤도에 섞는다.
 * 순서는 지도 데이터가 바뀔 때만 다시 계산한다(보는 중엔 그대로).
 */
export interface Satellite { trackId: number; memberIds: number[]; angle: number }
export interface Ring { kind: 'gold' | 'person'; memberIds: number[]; sats: Satellite[] }

export const GOLD_RING_MAX = 8
export const MAP_PERSON_RINGS = 3

export function orbitLayout(planet: Pick<Planet, 'artistId' | 'songs'>, memberOrder: number[], maxPersonRings = MAP_PERSON_RINGS): Ring[] {
  const shared = planet.songs.filter(s => s.memberIds.length >= 2)
    // 담은 사람이 많은 노래를 앞(안쪽 궤도)에
    .sort((a, b) => b.memberIds.length - a.memberIds.length || a.trackId - b.trackId)
  const rings: Ring[] = []
  if (shared.length > 0) {
    const inner = shared.length >= GOLD_RING_MAX ? shared.slice(0, Math.ceil(shared.length / 2)) : shared
    const outer = shared.length >= GOLD_RING_MAX ? shared.slice(inner.length) : []
    for (const group of [inner, outer]) {
      if (group.length === 0) continue
      const ids = [...new Set(group.flatMap(s => s.memberIds))]
      rings.push({ kind: 'gold', memberIds: ids, sats: group.map(s => ({ trackId: s.trackId, memberIds: s.memberIds, angle: 0 })) })
    }
  }

  // 사람별: 이 행성에 담은 곡 수(같이 담은 곡 포함), 위성은 혼자 담은 노래
  const total = new Map<number, number>()
  const solo = new Map<number, number[]>()
  for (const s of planet.songs) {
    for (const id of s.memberIds) total.set(id, (total.get(id) ?? 0) + 1)
    if (s.memberIds.length === 1) solo.set(s.memberIds[0], [...(solo.get(s.memberIds[0]) ?? []), s.trackId])
  }
  const rank = (id: number) => { const i = memberOrder.indexOf(id); return i < 0 ? Number.MAX_SAFE_INTEGER : i }
  const people = [...solo.keys()].sort((a, b) => total.get(a)! - total.get(b)! || rank(a) - rank(b))
  const personRings: Ring[] = people.map(id => ({ kind: 'person', memberIds: [id], sats: solo.get(id)!.map(t => ({ trackId: t, memberIds: [id], angle: 0 })) }))
  if (personRings.length > maxPersonRings) {
    const keep = personRings.slice(0, maxPersonRings - 1)
    const rest = personRings.slice(maxPersonRings - 1)
    keep.push({ kind: 'person', memberIds: rest.flatMap(r => r.memberIds), sats: rest.flatMap(r => r.sats) })
    rings.push(...keep)
  } else rings.push(...personRings)

  // 위성 각도: 궤도마다 고르게, 시작 각은 행성·궤도마다 달라 보이게(같은 데이터면 항상 같은 값)
  rings.forEach((ring, i) => {
    const start = hashAngle(planet.artistId * 31 + i * 7)
    const n = ring.sats.length
    ring.sats.forEach((s, k) => { s.angle = start + (2 * Math.PI * k) / n })
  })
  return rings
}

function hashAngle(n: number): number {
  let h = (n ^ 0x9e3779b9) >>> 0
  h = Math.imul(h ^ (h >>> 16), 0x45d9f3b) >>> 0
  h = (h ^ (h >>> 16)) >>> 0
  return ((h % 3600) / 3600) * 2 * Math.PI
}

/** 위성 화면 반지름(px): 금빛은 담은 사람이 많을수록 크게 */
export function satelliteRadius(sat: Satellite, kind: Ring['kind']): number {
  return kind === 'gold' ? 2.6 + 0.7 * (sat.memberIds.length - 2) : 2.2
}

/** 궤도 사이 간격(px)과 궤도 반지름 */
export const RING_GAP = 6
export const ringRadius = (planetR: number, i: number, grow = 1) => planetR + RING_GAP * grow * (i + 1)
