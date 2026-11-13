// 은하 지도 좌표 계산 (room-web.md 9단계). 공용 지도 좌표 0~1을 세계 좌표 0~WORLD로 펴고,
// 화면 = 세계 × scale + (tx, ty). 확대·이동은 이 세 값만 바꾼다.

export const WORLD = 1000
export const MIN_SCALE = 0.15
export const MAX_SCALE = 6

export interface View { scale: number; tx: number; ty: number }
export interface Size { width: number; height: number }
export interface Pt { x: number; y: number }

export const toWorld = (v: number) => v * WORLD

/** 행성 반지름(세계 단위): 노래가 많을수록 조금 크게 */
export function planetRadius(songs: number): number {
  return 12 + 4 * Math.sqrt(Math.max(1, songs))
}

export const clampScale = (s: number) => Math.min(MAX_SCALE, Math.max(MIN_SCALE, s))

/**
 * 점들이 모두 보이게 (⑤ 처음 위치: 방의 행성이 모두 보이게 확대).
 * 행성들의 실제 범위에 화면의 padFrac(가로·세로 각각)만큼 여백을 두고, 가로·세로 비율 중 작은 쪽으로 맞춘다.
 */
export function fitView(points: (Pt & { r?: number })[], size: Size, padFrac = 0.12): View {
  if (points.length === 0 || size.width <= 0 || size.height <= 0) {
    const s = clampScale(Math.min(size.width, size.height) / WORLD || 1)
    return { scale: s, tx: (size.width - WORLD * s) / 2, ty: (size.height - WORLD * s) / 2 }
  }
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const p of points) {
    const r = p.r ?? 0
    minX = Math.min(minX, p.x - r); maxX = Math.max(maxX, p.x + r)
    minY = Math.min(minY, p.y - r); maxY = Math.max(maxY, p.y + r)
  }
  // 행성 하나뿐이면 너무 크게 확대하지 않도록 최소 크기
  const w = Math.max(maxX - minX, 160), h = Math.max(maxY - minY, 160)
  const scale = clampScale(Math.min((size.width * (1 - 2 * padFrac)) / w, (size.height * (1 - 2 * padFrac)) / h, 2.4))
  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2
  return { scale, tx: size.width / 2 - cx * scale, ty: size.height / 2 - cy * scale }
}

/** +/− 버튼의 고정점: 화면 안 행성 중 가운데에 가장 가까운 것, 화면 안에 없으면 화면 가운데 (I-43 ②) */
export function zoomAnchor(screenPts: Pt[], size: Size): Pt {
  const c = { x: size.width / 2, y: size.height / 2 }
  let best: Pt | null = null, bd = Infinity
  for (const p of screenPts) {
    if (p.x < 0 || p.x > size.width || p.y < 0 || p.y > size.height) continue
    const d = (p.x - c.x) ** 2 + (p.y - c.y) ** 2
    if (d < bd) { bd = d; best = p }
  }
  return best ?? c
}

/** 화면 점 (sx, sy)을 고정한 채 배율을 factor만큼 바꾼다 (휠·두 손가락) */
export function zoomAt(v: View, sx: number, sy: number, factor: number): View {
  const scale = clampScale(v.scale * factor)
  const k = scale / v.scale
  return { scale, tx: sx - (sx - v.tx) * k, ty: sy - (sy - v.ty) * k }
}

export const pan = (v: View, dx: number, dy: number): View => ({ ...v, tx: v.tx + dx, ty: v.ty + dy })

/** 확대 단계: far(멀리, 궤도 없이 숫자) · mid(궤도) · near(위성 자세히). 이 값이 바뀔 때만 다시 그린다 */
export type ZoomLevel = 'far' | 'mid' | 'near'
export function zoomLevel(scale: number, spacing = Infinity): ZoomLevel {
  // 크기가 화면 기준이라 경계는 낮게: 휴대폰에서 방 전체를 맞추면 보통 0.3 근처.
  // 화면에 행성이 빽빽하면(평균 간격 spacing px이 좁으면) 배율과 상관없이 한 단계 멀리 본다: 궤도가 겹쳐 읽을 수 없으므로
  const byScale: ZoomLevel = scale < 0.22 ? 'far' : scale < 1.2 ? 'mid' : 'near'
  if (spacing < FAR_SPACING) return 'far'
  if (spacing < MID_SPACING && byScale === 'near') return 'mid'
  return byScale
}
export const FAR_SPACING = 48
export const MID_SPACING = 80

/** 화면 안 행성들의 평균 간격(px): 화면 넓이를 행성 수로 나눈 칸의 한 변 */
export function screenSpacing(points: Pt[], view: View, size: Size): number {
  let n = 0
  for (const p of points) {
    const sx = p.x * view.scale + view.tx, sy = p.y * view.scale + view.ty
    if (sx >= 0 && sx <= size.width && sy >= 0 && sy <= size.height) n++
  }
  return n === 0 ? Infinity : Math.sqrt((size.width * size.height) / n)
}

/**
 * 화면에서 읽을 수 있는 크기: 배율이 바뀌어도 행성·글자가 너무 작거나 커지지 않게 (멈춘 뒤 배율 기준).
 * 행성 화면 반지름은 노래 수에 따라 7~20px 근처, 확대할수록 조금 커진다.
 */
/** 화면 픽셀 기준 최솟값: 행성 지름 14px, 행성 이름 11px, 구역 이름 12px 이상 */
export const MIN_PLANET_R = 7
export const NAME_PX = 11
export const GALAXY_PX = 13

/**
 * 확대 체감 (I-37): 처음 맞춘 배율보다 확대하면 행성·위성·글자도 완만하게(√) 커진다. 축소 쪽은 1 그대로(최솟값 규칙),
 * 너무 커지지 않게 GROW_MAX에서 멈춘다.
 */
export const GROW_MAX = 1.7
export function zoomGrow(scale: number, fitScale: number): number {
  if (!(fitScale > 0)) return 1
  return Math.min(GROW_MAX, Math.max(1, Math.sqrt(scale / fitScale)))
}

/** 공전 (I-38): 궤도 한 바퀴 30~60초, 궤도마다 속도·방향을 조금씩 다르게 (행성·궤도 번호로 정해 늘 같다) */
export function orbitSpin(artistId: number, ring: number): { seconds: number; reverse: boolean } {
  const h = Math.abs(Math.imul(artistId + 1, 2654435761) ^ Math.imul(ring + 1, 40503)) >>> 0
  return { seconds: 30 + (h % 31), reverse: ((h >>> 5) + ring) % 3 === 0 }
}

/**
 * 행성 화면 반지름. scale 자리에는 "크기 기준 배율"을 넣는다: 확대 쪽 커짐은 zoomGrow가 맡으므로(I-43 ④)
 * 지도는 min(지금 배율, 처음 맞춘 배율)을 넘긴다 → 처음 맞춤보다 확대해도 여기서는 더 커지지 않는다.
 */
export function screenPlanetRadius(songs: number, scale: number): number {
  const zoomBoost = Math.sqrt(Math.min(2.2, Math.max(0.6, scale)))
  // 지름 14px 이상
  return Math.max(MIN_PLANET_R, (7 + 2.6 * Math.sqrt(Math.max(1, songs))) * zoomBoost)
}

/**
 * 이름 겹침 피하기: 노래가 많은 행성부터 이름 상자를 놓고, 이미 놓인 상자와 겹치면 그 이름은 숨긴다.
 * 입력은 화면 좌표. 돌려주는 값은 이름을 보여 줄 id 집합.
 */
export interface Box { x0: number; y0: number; x1: number; y1: number }
export function visibleLabels(items: { id: number; sx: number; sy: number; r: number; w: number; weight: number }[], h = 14, obstacles: Box[] = []): Set<number> {
  const placed: Box[] = [...obstacles]
  const out = new Set<number>()
  for (const it of [...items].sort((a, b) => b.weight - a.weight)) {
    const box = { x0: it.sx - it.w / 2, x1: it.sx + it.w / 2, y0: it.sy + it.r + 2, y1: it.sy + it.r + 2 + h }
    if (placed.some(p => box.x0 < p.x1 && box.x1 > p.x0 && box.y0 < p.y1 && box.y1 > p.y0)) continue
    placed.push(box)
    out.add(it.id)
  }
  return out
}

/** 이름 상자 (visibleLabels와 같은 자리): 다른 이름 계산의 장애물로 쓴다 */
export function labelBox(it: { sx: number; sy: number; r: number; w: number }, h = 14): Box {
  return { x0: it.sx - it.w / 2, x1: it.sx + it.w / 2, y0: it.sy + it.r + 2, y1: it.sy + it.r + 2 + h }
}

/** 별 배경: 방마다 같은 무늬가 나오게 시드로 만든 점 */
export function stars(seed: string, count = 140): { x: number; y: number; r: number; o: number }[] {
  let h = 2166136261
  for (const c of seed) { h ^= c.charCodeAt(0); h = Math.imul(h, 16777619) }
  const rand = () => { h ^= h << 13; h ^= h >>> 17; h ^= h << 5; return ((h >>> 0) % 10000) / 10000 }
  return Array.from({ length: count }, () => ({ x: rand() * WORLD * 1.4 - WORLD * 0.2, y: rand() * WORLD * 1.4 - WORLD * 0.2, r: 0.6 + rand() * 1.6, o: 0.25 + rand() * 0.55 }))
}
