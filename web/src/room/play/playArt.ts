// 놀이터 그림 계산 (화면과 떨어뜨려 테스트)

/** 두 행성 중심 사이 거리: 점수가 높을수록 많이 겹친다. 고르기 전(null)은 반쯤 겹침. 0%여도 살짝 닿는다 */
export function lensGap(r: number, score: number | null): number {
  if (score == null) return r * 1.1
  const s = Math.max(0, Math.min(100, score)) / 100
  return 2 * r * (1 - 0.8 * s) - (s === 0 ? 4 : 0)
}

/** 두 명 고르기: 이미 고른 사람을 다시 누르면 빼고, 셋째를 누르면 가장 먼저 고른 사람이 빠진다 */
export function togglePick(prev: number[], id: number): number[] {
  return prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id].slice(-2)
}
