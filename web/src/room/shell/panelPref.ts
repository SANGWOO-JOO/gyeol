// PC 오른쪽 판 접기 (11-14): 접힘 여부는 방과 상관없이 이 브라우저에 기억한다. 저장소를 못 쓰면 펼친 상태로 시작
export const PANEL_KEY = 'gyeol.panelCollapsed'
type Store = Pick<Storage, 'getItem' | 'setItem'> & Partial<Pick<Storage, 'removeItem'>> | null | undefined
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

// PC 오른쪽 판 너비 조절 (판 왼쪽 경계를 끌기). 방과 상관없이 이 브라우저에 기억한다
export const PANEL_WIDTH_KEY = 'gyeol.panelWidth'
export const PANEL_MIN = 320
export const PANEL_MAX = 560
/** 지도가 너무 좁아지지 않게: 판은 화면 너비의 40%까지 */
export const PANEL_MAX_RATIO = 0.4
/** 키보드 좌우 화살표 한 번에 움직이는 양 */
export const PANEL_STEP = 16
/** 이 너비부터 판 본문 글자를 한 단계 키운다 (shell.module.css @container panel) */
export const PANEL_LARGE_TEXT = 420

/** 기본 너비: 넓은 화면(1600px 이상)은 440px, 그보다 작으면 예전 고정값 400px */
export function defaultPanelWidth(viewport: number): number {
  return clampPanelWidth(viewport >= 1600 ? 440 : 400, viewport)
}
/** 320~560px, 그리고 화면의 40% 이하. 화면이 좁아 40%가 320보다 작으면 320 */
export function panelMax(viewport: number): number {
  return Math.max(PANEL_MIN, Math.min(PANEL_MAX, Math.floor(viewport * PANEL_MAX_RATIO)))
}
export function clampPanelWidth(w: number, viewport: number): number {
  if (!Number.isFinite(w)) return PANEL_MIN
  return Math.min(panelMax(viewport), Math.max(PANEL_MIN, Math.round(w)))
}
/** 기억한 너비(없거나 잘못됐으면 null). 화면에 맞춘 값은 clampPanelWidth로 */
export function readPanelWidth(s: Store = store()): number | null {
  try {
    const v = Number(s?.getItem(PANEL_WIDTH_KEY))
    return s?.getItem(PANEL_WIDTH_KEY) != null && Number.isFinite(v) && v > 0 ? v : null
  } catch { return null }
}
export function writePanelWidth(w: number | null, s: Store = store()): void {
  try {
    if (w == null) s?.removeItem?.(PANEL_WIDTH_KEY)
    else s?.setItem(PANEL_WIDTH_KEY, String(Math.round(w)))
  } catch { /* 기억 못 해도 이번 화면에서는 그대로 */ }
}
/** 지금 보여 줄 너비: 기억한 값(없으면 기본값)을 이 화면 범위에 맞춤 */
export function panelWidthFor(viewport: number, stored: number | null): number {
  return stored == null ? defaultPanelWidth(viewport) : clampPanelWidth(stored, viewport)
}
/** 끌기: 판은 오른쪽에 있으니 왼쪽으로 끌수록(dx<0) 넓어진다 */
export function dragPanelWidth(startWidth: number, startX: number, x: number, viewport: number): number {
  return clampPanelWidth(startWidth + (startX - x), viewport)
}
/** 키보드: ←는 넓게, →는 좁게, Home은 최소, End는 최대. 다른 키는 null */
export function keyPanelWidth(w: number, key: string, viewport: number): number | null {
  if (key === 'ArrowLeft') return clampPanelWidth(w + PANEL_STEP, viewport)
  if (key === 'ArrowRight') return clampPanelWidth(w - PANEL_STEP, viewport)
  if (key === 'Home') return PANEL_MIN
  if (key === 'End') return panelMax(viewport)
  return null
}
