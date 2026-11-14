// 행성 겹침 풀기 (11-14, 방안 A): 화면에 그릴 때만 행성을 조금 밀어 바깥 궤도끼리 겹치지 않게 한다. 서버 좌표는 그대로.
// 결정적(무작위 없음): 같은 입력이면 늘 같은 결과. 원래 자리로 당기는 힘을 두고, 자기 구역 중심이 늘 가장 가깝게(구역을 넘지 않게) 한다.

export interface DeclutterInput { id: number; x: number; y: number; r: number; galaxyId?: number | null }
export interface GalaxyCenter { id: number; x: number; y: number }

/**
 * points: 세계 좌표와 반지름(세계 단위, 바깥 궤도 + 위성 + 여백의 절반). 돌려주는 값: id → 새 세계 좌표.
 * 짝마다 겹친 만큼 반씩 밀고(반복), 매 반복 원래 자리 쪽으로 pull만큼 당긴다. 자기 구역보다 다른 구역 중심이 더 가까워지면 그 이동은 되돌린다
 */
export function declutter(points: DeclutterInput[], galaxies: GalaxyCenter[] = [], opt: { iterations?: number; pull?: number } = {}): Map<number, { x: number; y: number }> {
  const it = opt.iterations ?? 200
  const pull = opt.pull ?? 0.04
  const n = points.length
  const ps = [...points].sort((a, b) => a.id - b.id) // 입력 순서와 무관하게 같은 결과
  const x = ps.map(p => p.x), y = ps.map(p => p.y)
  const gal = new Map(galaxies.map(g => [g.id, g]))
  const nearestOk = (i: number, nx: number, ny: number) => {
    const own = ps[i].galaxyId != null ? gal.get(ps[i].galaxyId!) : undefined
    if (!own) return true
    const d0 = (own.x - nx) ** 2 + (own.y - ny) ** 2
    for (const g of galaxies) if (g.id !== own.id && (g.x - nx) ** 2 + (g.y - ny) ** 2 < d0) return false
    return true
  }
  const dx = new Float64Array(n), dy = new Float64Array(n)
  for (let k = 0; k < it; k++) {
    let moved = false
    // 당기는 힘은 앞쪽 반복에서만, 뒤로 갈수록 0 (밀기와 맞서 겹친 채 멈추지 않게)
    const pk = k < it * 0.5 ? pull * (1 - k / (it * 0.5)) : 0
    dx.fill(0); dy.fill(0)
    for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) {
      let vx = x[j] - x[i], vy = y[j] - y[i]
      const need = ps[i].r + ps[j].r
      if (vx > need || vx < -need || vy > need || vy < -need) continue
      let d = Math.sqrt(vx * vx + vy * vy)
      if (d >= need) continue
      if (d < 1e-9) { const a = ((ps[i].id * 7919 + ps[j].id * 104729) % 360) * Math.PI / 180; vx = Math.cos(a); vy = Math.sin(a); d = 1 } // 같은 자리: 정해진 방향
      const push = (need - d) / 2 / d
      dx[i] -= vx * push; dy[i] -= vy * push; dx[j] += vx * push; dy[j] += vy * push
      moved = true
    }
    for (let i = 0; i < n; i++) {
      // 1.01: 반씩 민 뒤 부동소수 오차로 딱 붙은 채 남지 않게 조금 더
      const nx = x[i] + dx[i] * 1.01 + (ps[i].x - x[i]) * pk
      const ny = y[i] + dy[i] * 1.01 + (ps[i].y - y[i]) * pk
      if (nearestOk(i, nx, ny)) { x[i] = nx; y[i] = ny }
      else { x[i] += (ps[i].x - x[i]) * pk; y[i] += (ps[i].y - y[i]) * pk } // 구역을 넘는 이동은 하지 않고 당기기만
    }
    if (!moved && pk === 0) break
  }
  return new Map(ps.map((p, i) => [p.id, { x: x[i], y: y[i] }]))
}

/** 겹친 쌍 수 (검사·측정용) */
export function overlapCount(points: { id: number; x: number; y: number; r: number }[], at: Map<number, { x: number; y: number }>, tol = 1e-6): number {
  let c = 0
  for (let i = 0; i < points.length; i++) for (let j = i + 1; j < points.length; j++) {
    const a = at.get(points[i].id)!, b = at.get(points[j].id)!
    if (Math.hypot(a.x - b.x, a.y - b.y) < points[i].r + points[j].r - tol) c++
  }
  return c
}

/** 다시 계산할 구간: 확대 단계 + 배율을 1/4옥타브로 묶은 칸. 구간 안에서는 같은 배치를 쓴다 */
export function declutterBucket(scale: number, level: string): string {
  return `${level}:${Math.floor(Math.log2(Math.max(1e-6, scale)) * 4)}`
}
/** 구간의 가장 낮은 배율 (세계 단위 반지름이 가장 커서 구간 안 어디서도 겹치지 않는 쪽) */
export function bucketMinScale(scale: number): number {
  return Math.pow(2, Math.floor(Math.log2(Math.max(1e-6, scale)) * 4) / 4)
}
