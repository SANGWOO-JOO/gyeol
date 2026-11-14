// 가수 판 (11-14 개편): 우리 방 요약·곡 순서 계산 (화면과 떨어뜨려 테스트)

/** 우리 방에서 이 가수: 사람마다 혼자 담은 곡 수, 같이 담은 곡 수. 사람 순서는 방 멤버 순서 */
export function roomSummary(songs: { memberIds: number[] }[], memberOrder: number[]): { solo: { memberId: number; count: number }[]; shared: number; total: number } {
  const solo = new Map<number, number>()
  let shared = 0
  for (const s of songs) {
    if (s.memberIds.length >= 2) shared++
    else if (s.memberIds.length === 1) solo.set(s.memberIds[0], (solo.get(s.memberIds[0]) ?? 0) + 1)
  }
  const rank = (id: number) => { const i = memberOrder.indexOf(id); return i < 0 ? Number.MAX_SAFE_INTEGER : i }
  return { solo: [...solo].map(([memberId, count]) => ({ memberId, count })).sort((a, b) => rank(a.memberId) - rank(b.memberId)), shared, total: songs.length }
}

/** 곡 순서: 같이 담은 곡(담은 사람 많은 순)이 맨 위, 나머지는 원래 순서 */
export function sharedFirst<T extends { memberIds: number[] }>(songs: T[]): T[] {
  return songs.map((s, i) => ({ s, i })).sort((a, b) => {
    const ga = a.s.memberIds.length >= 2 ? a.s.memberIds.length : 0, gb = b.s.memberIds.length >= 2 ? b.s.memberIds.length : 0
    return gb - ga || a.i - b.i
  }).map(x => x.s)
}

/**
 * 우리 방 알약 (11-14): 사람마다 이 가수 곡 전체 수(같이 담은 곡 포함). "나"가 맨 앞(담았을 때만), 나머지는 곡 많은 순·같으면 참가 순.
 * shared = 두 명 이상이 담은 곡 수(조합 무관). 보이는 사람은 max명, 나머지는 +n
 */
export function roomPills(songs: { memberIds: number[] }[], memberOrder: number[], meId: number, max = 3): {
  people: { memberId: number; count: number }[]; shown: { memberId: number; count: number }[]; hidden: number; shared: number
} {
  const total = new Map<number, number>()
  let shared = 0
  for (const s of songs) {
    for (const id of s.memberIds) total.set(id, (total.get(id) ?? 0) + 1)
    if (s.memberIds.length >= 2) shared++
  }
  const rank = (id: number) => { const i = memberOrder.indexOf(id); return i < 0 ? Number.MAX_SAFE_INTEGER : i }
  const people = [...total].map(([memberId, count]) => ({ memberId, count }))
    .sort((a, b) => (b.memberId === meId ? 1 : 0) - (a.memberId === meId ? 1 : 0) || b.count - a.count || rank(a.memberId) - rank(b.memberId))
  return { people, shown: people.slice(0, max), hidden: Math.max(0, people.length - max), shared }
}

/**
 * 고리 조각 각도(도): 비율대로 나누되 조각마다 최소 minDeg, 조각 사이 gapDeg 틈. 조각이 하나면 틈 없이 한 바퀴
 * (조각이 너무 많아 최소 각도를 다 못 주면 남은 각도를 고르게)
 */
export function ringArcs(counts: number[], minDeg = 10, gapDeg = 3): { start: number; sweep: number }[] {
  const parts = counts.filter(c => c > 0)
  if (parts.length === 0) return []
  if (parts.length === 1) return [{ start: 0, sweep: 360 }]
  const free = 360 - gapDeg * parts.length
  const total = parts.reduce((a, b) => a + b, 0)
  let sweeps = parts.map(c => (c / total) * free)
  if (minDeg * parts.length >= free) sweeps = parts.map(() => free / parts.length)
  else {
    // 작은 조각을 최소 각도로 올리고, 모자란 만큼 큰 조각에서 비율대로 뺀다
    const small = sweeps.map(s => s < minDeg)
    const need = sweeps.reduce((a, s, i) => a + (small[i] ? minDeg - s : 0), 0)
    const bigTotal = sweeps.reduce((a, s, i) => a + (small[i] ? 0 : s), 0)
    sweeps = sweeps.map((s, i) => (small[i] ? minDeg : s - (s / bigTotal) * need))
  }
  let at = 0
  return sweeps.map(sweep => { const r = { start: at, sweep }; at += sweep + gapDeg; return r })
}
