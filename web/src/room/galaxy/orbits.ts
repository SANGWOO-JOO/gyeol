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

/** 위성 표지(가까이·화면 안): 반지름 6.5px × 위성 확대, 테두리 1.6px. 지도와 같은 값 */
export const COVER_R = 6.5
export const COVER_PAD = 1.6
/** 위성끼리(이웃 궤도·같은 궤도) 띄울 여백(px) */
export const SAT_GAP = 4

/** 이 궤도에서 가장 큰 위성 지름(px): 표지면 표지+테두리, 점이면 점 (sg = 위성 확대, near면 점도 1.3배) */
export function satDiameter(ring: Ring, sg: number, covers: boolean, near = covers): number {
  if (covers) return 2 * (COVER_R + COVER_PAD) * sg
  const k = (near ? 1.3 : 1) * sg
  return 2 * Math.max(...ring.sats.map(s => satelliteRadius(s, ring.kind))) * k
}

/**
 * 궤도 반지름들(px, 11-14): 이웃 궤도 사이 ≥ (두 궤도 위성 반지름 합 + 여백), 첫 궤도는 행성 면에서 위성 반지름 + 여백,
 * 같은 궤도의 위성끼리(고른 각도) 현의 길이 ≥ 지름 + 여백. 셋 다 기존 간격(RING_GAP × grow)보다 좁아지지는 않는다.
 * 위성은 공전하며 서로 다른 속도로 돌므로, 이웃 궤도 위성이 같은 방향에 와도 겹치지 않게 반지름 차이로 막는다.
 */
export function ringRadii(planetR: number, rings: Ring[], grow: number, sg: number, covers: boolean, near = covers): number[] {
  const out: number[] = []
  let prev = planetR, prevHalf = 0
  for (const ring of rings) {
    const d = satDiameter(ring, sg, covers, near)
    let r = prev + Math.max(RING_GAP * grow, prevHalf + d / 2 + SAT_GAP)
    const n = ring.sats.length
    if (n >= 2) r = Math.max(r, (d + SAT_GAP) / (2 * Math.sin(Math.PI / n)))
    out.push(r)
    prev = r; prevHalf = d / 2
  }
  return out
}
