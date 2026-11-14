// 지도 배경 (11-14 개편): 겹겹의 별(시차)·반짝이는 별·혜성 경로·새 곡 찾기. 화면과 떨어뜨려 테스트한다. 무작위 대신 씨앗으로 늘 같은 결과

/** 씨앗 → 0~1 수열 (mulberry32) */
export function rng(seed: number): () => number {
  let a = seed >>> 0
  return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296 }
}
export const hashSeed = (s: string) => { let h = 2166136261; for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return h >>> 0 }

/** 별 겹: 먼 겹일수록 작고 흐리고 많으며, 끌 때 느리게(시차 factor) 움직인다 */
export const STAR_LAYERS = [
  { tile: 420, count: 70, r: [0.4, 0.8], o: [0.18, 0.4], factor: 0.15 },
  { tile: 560, count: 26, r: [0.7, 1.2], o: [0.3, 0.6], factor: 0.4 },
  { tile: 700, count: 9, r: [1.1, 1.7], o: [0.55, 0.85], factor: 0.75 },
] as const

/** 한 겹의 별 타일 (CSS 배경으로 반복). 같은 씨앗이면 같은 별 */
export function starTile(seed: number, layer: number): { tile: number; stars: { x: number; y: number; r: number; o: number }[] } {
  const L = STAR_LAYERS[layer]
  const r = rng(seed + layer * 7919)
  return { tile: L.tile, stars: Array.from({ length: L.count }, () => ({ x: r() * L.tile, y: r() * L.tile, r: L.r[0] + r() * (L.r[1] - L.r[0]), o: L.o[0] + r() * (L.o[1] - L.o[0]) })) }
}
export function starTileUrl(seed: number, layer: number): string {
  const { tile, stars } = starTile(seed, layer)
  const dots = stars.map(s => `<circle cx='${s.x.toFixed(1)}' cy='${s.y.toFixed(1)}' r='${s.r.toFixed(2)}' fill='%23e6dcff' fill-opacity='${s.o.toFixed(2)}'/>`).join('')
  return `url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='${tile}' height='${tile}'>${dots}</svg>")`
}

/** 시차: 지도가 (tx, ty)만큼 움직였을 때 겹마다 배경 위치. 먼 겹은 덜 움직인다 */
export function parallaxPositions(tx: number, ty: number): string {
  return STAR_LAYERS.map(L => `${(tx * L.factor).toFixed(1)}px ${(ty * L.factor).toFixed(1)}px`).join(', ')
}

/** 천천히 반짝이는 별 몇 개 (화면 % 자리, 지연·주기) */
export function twinkles(seed: number, n = 9): { left: number; top: number; size: number; delay: number; dur: number }[] {
  const r = rng(seed ^ 0x9e3779b9)
  return Array.from({ length: n }, () => ({ left: 3 + r() * 94, top: 3 + r() * 94, size: 1.6 + r() * 1.6, delay: r() * 6, dur: 3.5 + r() * 4 }))
}

/**
 * 담기 혜성 경로: 화면 가운데 → 목표 방향으로 목표 너머 화면 밖 점에서 출발해 목표로 들어온다(가까운 가장자리에서 날아옴).
 * 씨앗으로 방향을 조금 비튼다. 목표가 가운데면 왼쪽 위에서. 출발점은 가운데에서 대각선의 0.75배 이상 떨어져 늘 화면 밖이다
 */
export function cometPath(target: { x: number; y: number }, view: { width: number; height: number }, seed = 0): { sx: number; sy: number; ex: number; ey: number; angle: number } {
  const cx = view.width / 2, cy = view.height / 2
  let dx = target.x - cx, dy = target.y - cy
  if (Math.hypot(dx, dy) < 1) { dx = -1; dy = -0.6 }
  const tilt = ((seed % 7) - 3) * 0.08
  const a = Math.atan2(dy, dx) + tilt
  const far = Math.hypot(view.width, view.height) * 0.75
  const sx = target.x + Math.cos(a) * far, sy = target.y + Math.sin(a) * far
  return { sx, sy, ex: target.x, ey: target.y, angle: (Math.atan2(target.y - sy, target.x - sx) * 180) / Math.PI }
}

/** 새로 담긴 곡: 이전에 본 곡 키(행성:곡)에 없던 것. 첫 지도(prev가 null)는 없음으로 본다. max개까지(최근 위주로 뒤에서) */
export function newSongs(prev: Set<string> | null, planets: { artistId: number; songs: { trackId: number; memberIds: number[] }[] }[], max = 3): { artistId: number; trackId: number; memberId: number }[] {
  if (!prev) return []
  const out: { artistId: number; trackId: number; memberId: number }[] = []
  for (const p of planets) for (const s of p.songs) if (!prev.has(`${p.artistId}:${s.trackId}`)) out.push({ artistId: p.artistId, trackId: s.trackId, memberId: s.memberIds[s.memberIds.length - 1] ?? -1 })
  return out.slice(-max)
}
export const songKeys = (planets: { artistId: number; songs: { trackId: number }[] }[]) => new Set(planets.flatMap(p => p.songs.map(s => `${p.artistId}:${s.trackId}`)))

/** 장식 혜성 간격(ms): 30~60초, 씨앗과 순번으로 정해진다 */
export const decorDelay = (seed: number, i: number) => 30000 + Math.floor(rng(seed + i * 104729)() * 30000)
