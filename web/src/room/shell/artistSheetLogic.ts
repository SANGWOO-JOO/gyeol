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
