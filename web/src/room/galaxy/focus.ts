// I-26 사람 선택(캔버스 4-PC″): PC 참가자 탭·지도 색 안내에서 사람을 누르면 그 사람이 담은 노래만 밝게.
// 선택·해제와 밝게/흐리게 판단은 화면과 떨어뜨려 테스트한다.

/** 같은 사람을 다시 누르면 해제, 다른 사람이면 그 사람으로 */
export function toggleFocus(cur: number | null, id: number): number | null {
  return cur === id ? null : id
}

/** 선택된 사람이 방에서 나갔으면(지도 사람 목록에 없으면) 해제 */
export function keepFocus(cur: number | null, memberIds: number[]): number | null {
  return cur != null && memberIds.includes(cur) ? cur : null
}

/** 곡(위성) 하나: 선택이 없거나 선택된 사람이 담은 곡이면 밝게 */
export function songLit(memberIds: number[], focus: number | null): boolean {
  return focus == null || memberIds.includes(focus)
}

/** 행성: 선택된 사람이 담은 곡이 하나라도 있으면 밝게 (여러 사람이 담은 행성도) */
export function planetLit(p: { songs: { memberIds: number[] }[] }, focus: number | null): boolean {
  return focus == null || p.songs.some(s => s.memberIds.includes(focus))
}

/** 궤도 선: 사람 궤도는 그 사람 것만, 금빛(같이 담음) 궤도는 그 궤도에 밝은 위성이 있을 때만 */
export function ringLit(ring: { kind: string; memberIds: number[]; sats: { memberIds: number[] }[] }, focus: number | null): boolean {
  if (focus == null) return true
  if (ring.kind === 'gold') return ring.sats.some(s => s.memberIds.includes(focus))
  return ring.memberIds.includes(focus)
}
