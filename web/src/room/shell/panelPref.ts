// PC 오른쪽 판 접기 (11-14): 접힘 여부는 방과 상관없이 이 브라우저에 기억한다. 저장소를 못 쓰면 펼친 상태로 시작
export const PANEL_KEY = 'gyeol.panelCollapsed'
type Store = Pick<Storage, 'getItem' | 'setItem'> | null | undefined
const store = (): Store => { try { return typeof localStorage === 'undefined' ? null : localStorage } catch { return null } }

export function readCollapsed(s: Store = store()): boolean {
  try { return s?.getItem(PANEL_KEY) === '1' } catch { return false }
}
export function writeCollapsed(v: boolean, s: Store = store()): void {
  try { s?.setItem(PANEL_KEY, v ? '1' : '0') } catch { /* 기억 못 해도 화면은 그대로 */ }
}

/** 판이 저절로 펼쳐지는 경우: 탭을 고름(+ 노래 담기 = 검색 포함), 지도에서 가수·위성·근처 가수 판이 열림(artist·me) */
export function expandsPanel(e: { kind: 'tab' } | { kind: 'sheet'; sheet: string | null | undefined }): boolean {
  return e.kind === 'tab' || e.sheet === 'artist' || e.sheet === 'me'
}

/** 지도 크기가 바뀔 때: 가로만 바뀌었으면(판 접기·펴기) 보던 중심을 유지하도록 옮길 tx, 아니면 null(다시 맞춤 규칙 그대로) */
export function keepCenterShift(prev: { width: number; height: number } | null, next: { width: number; height: number }): number | null {
  if (!prev || prev.width === 0 || prev.height !== next.height || prev.width === next.width) return null
  return (next.width - prev.width) / 2
}
