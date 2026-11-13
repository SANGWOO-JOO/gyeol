import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import type { MemberView, RoomMap } from '../roomApi'
import { memberColor } from '../roomApi'
import { planetPeople } from '../roomLogic'
import { fitView, labelBox, pan, planetRadius, screenPlanetRadius, stars, toWorld, visibleLabels, zoomAt, zoomLevel, type View } from './galaxyMath'
import g from './galaxy.module.css'

/**
 * 은하 지도 (room-plan 9절 ⑤⑦⑧⑨⑫, room-web.md 9단계). 방에 담긴 가수의 행성만, 공용 지도 좌표 그대로.
 * 확대·이동 중에는 React를 다시 그리지 않고 그룹 변환만 바꾸고, 멈춘 뒤(0.15초) 그 배율에 맞춰
 * 행성·글자를 화면에서 읽을 수 있는 크기로 다시 그린다. 이름은 겹치지 않는 것만 보인다.
 */
export function GalaxyMap({ map, meId, roomId, onSelectArtist }: {
  map: RoomMap; meId: number; roomId: string; onSelectArtist?: (artistId: number) => void
}) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const layerRef = useRef<SVGGElement>(null)
  const viewRef = useRef<View>({ scale: 1, tx: 0, ty: 0 })
  const [settled, setSettled] = useState<View>(viewRef.current)
  const settleTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const [size, setSize] = useState({ width: 0, height: 0 })
  const fitted = useRef(false)

  const members = useMemo(() => new Map<number, MemberView>(map.members.map(m => [m.id, m])), [map.members])
  const planets = useMemo(() => map.planets.map(p => ({ ...p, wx: toWorld(p.x), wy: toWorld(p.y), people: planetPeople(p) })), [map.planets])
  const galaxies = useMemo(() => map.galaxies.filter(x => x.name).map(x => ({ ...x, wx: toWorld(x.x), wy: toWorld(x.y) })), [map.galaxies])
  const ghosts = useMemo(() => map.ghosts.map(x => ({ ...x, wx: toWorld(x.x), wy: toWorld(x.y) })), [map.ghosts])
  const starField = useMemo(() => stars(roomId), [roomId])

  const apply = useCallback((v: View, settleNow = false) => {
    viewRef.current = v
    layerRef.current?.setAttribute('transform', `translate(${v.tx} ${v.ty}) scale(${v.scale})`)
    clearTimeout(settleTimer.current)
    if (settleNow) setSettled(v)
    else settleTimer.current = setTimeout(() => setSettled(viewRef.current), 150)
  }, [])
  useEffect(() => () => clearTimeout(settleTimer.current), [])

  const fit = useCallback(() => {
    if (size.width === 0) return
    apply(fitView(planets.map(p => ({ x: p.wx, y: p.wy, r: planetRadius(p.songs.length) + 30 })), size), true)
  }, [apply, planets, size])

  useLayoutEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setSize({ width: e.contentRect.width, height: e.contentRect.height }))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  // ⑤ 처음(또는 0곡에서 첫 행성이 생겼을 때) 방의 행성이 모두 보이게.
  // 사용자가 아직 지도를 움직이지 않았다면 화면 크기가 바뀔 때(회전·창 크기)도 다시 맞춘다
  const touched = useRef(false)
  useEffect(() => {
    if (size.width === 0 || planets.length === 0) return
    if (fitted.current && touched.current) return
    fitted.current = true
    fit()
  }, [size, planets.length, fit])
  const refit = () => { touched.current = false; fit() }

  // ── 손가락·마우스 (라이브러리 없이 포인터 이벤트) ──
  const pointers = useRef(new Map<number, { x: number; y: number }>())
  const gesture = useRef<{ moved: number; downTarget: EventTarget | null; pinch: number | null }>({ moved: 0, downTarget: null, pinch: null })
  const local = (e: { clientX: number; clientY: number }) => {
    const r = wrapRef.current!.getBoundingClientRect()
    return { x: e.clientX - r.left, y: e.clientY - r.top }
  }

  function onPointerDown(e: React.PointerEvent) {
    if (e.button !== 0 && e.pointerType === 'mouse') return
    wrapRef.current?.setPointerCapture(e.pointerId)
    pointers.current.set(e.pointerId, local(e))
    if (pointers.current.size === 1) gesture.current = { moved: 0, downTarget: e.target, pinch: null }
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()]
      gesture.current.pinch = Math.hypot(a.x - b.x, a.y - b.y)
    }
  }
  function onPointerMove(e: React.PointerEvent) {
    const prev = pointers.current.get(e.pointerId)
    if (!prev) return
    const p = local(e)
    pointers.current.set(e.pointerId, p)
    if (pointers.current.size === 1) {
      const dx = p.x - prev.x, dy = p.y - prev.y
      gesture.current.moved += Math.abs(dx) + Math.abs(dy)
      if (gesture.current.moved >= 8) touched.current = true
      apply(pan(viewRef.current, dx, dy))
    } else if (pointers.current.size === 2 && gesture.current.pinch) {
      const [a, b] = [...pointers.current.values()]
      const d = Math.hypot(a.x - b.x, a.y - b.y)
      gesture.current.moved += 10
      touched.current = true
      apply(zoomAt(viewRef.current, (a.x + b.x) / 2, (a.y + b.y) / 2, d / gesture.current.pinch))
      gesture.current.pinch = d
    }
  }
  function onPointerUp(e: React.PointerEvent) {
    const wasSingle = pointers.current.size === 1
    pointers.current.delete(e.pointerId)
    if (pointers.current.size < 2) gesture.current.pinch = null
    // 거의 움직이지 않았으면 탭: 누른 곳의 행성·유령 행성을 연다
    if (wasSingle && gesture.current.moved < 8) {
      const hit = (gesture.current.downTarget as Element | null)?.closest?.('[data-artist]')
      const id = hit ? Number(hit.getAttribute('data-artist')) : NaN
      if (!Number.isNaN(id)) onSelectArtist?.(id)
    }
  }

  useEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      touched.current = true
      const p = local(e)
      apply(zoomAt(viewRef.current, p.x, p.y, Math.exp(-e.deltaY * 0.0015)))
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [apply])

  const zoomBy = (f: number) => { touched.current = true; apply(zoomAt(viewRef.current, size.width / 2, size.height / 2, f), true) }
  const onKey = (id: number) => (e: KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelectArtist?.(id) } }

  // ── 멈춘 배율 기준 크기·이름 ──
  const s = settled.scale
  const u = 1 / s // 화면 1px = 세계 u
  const level = zoomLevel(s)
  const radius = useMemo(() => new Map(planets.map(p => [p.artistId, screenPlanetRadius(p.songs.length, s)])), [planets, s])
  const nameItems = useMemo(() => planets.map(p => ({
    id: p.artistId, sx: p.wx * s + settled.tx, sy: p.wy * s + settled.ty, r: radius.get(p.artistId)!,
    w: Math.min(130, Array.from(p.name).length * 9 + 8), weight: p.songs.length + (p.people.memberIds.includes(meId) ? 0.5 : 0),
  })), [planets, s, settled.tx, settled.ty, radius, meId])
  // 이름 자리 정하기: 행성 몸체를 먼저 놓고 → 행성 이름(노래 많은 순) → 유령 이름 → 구역 이름. 겹치면 숨긴다
  const { labels, ghostLabels, galaxyLabels } = useMemo(() => {
    const bodies = nameItems.map(it => ({ x0: it.sx - it.r - 5, x1: it.sx + it.r + 5, y0: it.sy - it.r - 10, y1: it.sy + it.r }))
    const labels = level === 'far' ? new Set<number>() : visibleLabels(nameItems, 15, bodies)
    const taken = [...bodies, ...nameItems.filter(it => labels.has(it.id)).map(it => labelBox(it, 15))]
    let ghostLabels = new Set<number>()
    if (level !== 'far') {
      const gItems = ghosts.map(x => ({ id: x.artistId, sx: x.wx * s + settled.tx, sy: x.wy * s + settled.ty, r: 8, w: Math.min(120, Array.from(x.name).length * 8 + 8), weight: 0 }))
      ghostLabels = visibleLabels(gItems, 13, taken)
      taken.push(...gItems.map(it => ({ x0: it.sx - 9, x1: it.sx + 9, y0: it.sy - 9, y1: it.sy + 9 })), ...gItems.filter(it => ghostLabels.has(it.id)).map(it => labelBox(it, 13)))
    }
    const count = new Map<number, number>()
    planets.forEach(p => { if (p.galaxyId != null) count.set(p.galaxyId, (count.get(p.galaxyId) ?? 0) + p.songs.length) })
    const galaxyLabels = visibleLabels(galaxies.map(x => ({
      id: x.id, sx: x.wx * s + settled.tx, sy: x.wy * s + settled.ty - 38, r: 0,
      w: Array.from(x.name ?? '').length * 13 + 8, weight: count.get(x.id) ?? 0,
    })), 18, taken)
    return { labels, ghostLabels, galaxyLabels }
  }, [nameItems, ghosts, galaxies, planets, s, settled.tx, settled.ty, level])

  const v = viewRef.current
  return (
    <div className={g.mapWrap}>
      <div ref={wrapRef} className={g.map} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}>
        <svg width="100%" height="100%" role="group" aria-label={`우리 음악 은하. 행성 ${planets.length}개. 행성을 누르면 자세히 보여요`}>
          <defs>
            <radialGradient id="gl-planet" cx="35%" cy="30%" r="75%">
              <stop offset="0%" stopColor="#4a4385" />
              <stop offset="100%" stopColor="#221e45" />
            </radialGradient>
          </defs>
          <g ref={layerRef} transform={`translate(${v.tx} ${v.ty}) scale(${v.scale})`}>
            <g aria-hidden="true">
              {starField.map((st, i) => <circle key={i} cx={st.x} cy={st.y} r={st.r * u} fill="#2A2650" opacity={st.o} />)}
            </g>
            <g aria-hidden="true" className={g.galaxyNames}>
              {galaxies.filter(x => galaxyLabels.has(x.id)).map(x => <text key={x.id} x={x.wx} y={x.wy - 22 * u} fontSize={13 * u} textAnchor="middle">{x.name}</text>)}
            </g>
            {/* ⑧ 근처 추천 가수: 흐린 점선 유령 행성 */}
            {level !== 'far' && ghosts.map(x => (
              <g key={`gh${x.artistId}`} className={g.ghost} data-artist={x.artistId} transform={`translate(${x.wx} ${x.wy})`}
                role="button" tabIndex={0} aria-label={`근처 행성 ${x.name} (아직 우리 우주에 없음)`} onKeyDown={onKey(x.artistId)}>
                <circle className={g.ghostHit} r={20 * u} />
                <circle r={8 * u} strokeWidth={1.2 * u} strokeDasharray={`${3 * u} ${4 * u}`} />
                {ghostLabels.has(x.artistId) && <text y={(8 + 12) * u} fontSize={10 * u} textAnchor="middle">{x.name}</text>}
              </g>
            ))}
            {planets.map(p => {
              const r = radius.get(p.artistId)! * u
              const mine = p.people.memberIds.includes(meId)
              const who = p.people.memberIds.map(id => members.get(id)?.nickname).filter(Boolean).join(', ')
              return (
                <g key={p.artistId} className={`${g.planetNode} ${p.preparing ? g.preparingNode : ''}`} data-artist={p.artistId}
                  transform={`translate(${p.wx} ${p.wy})`} role="button" tabIndex={0} onKeyDown={onKey(p.artistId)}
                  aria-label={`${p.name}, 노래 ${p.songs.length}곡${who ? `, ${who}` : ''}${p.people.shared ? `, 같이 담은 노래 ${p.people.shared}곡` : ''}${p.preparing ? ', 우주에 올리는 중' : ''}`}>
                  <circle className={g.planetHit} r={r + 10 * u} />
                  <circle className={g.planetBody} r={r} fill={p.preparing ? 'none' : 'url(#gl-planet)'} stroke={mine ? '#E6DCFF' : '#5E5794'}
                    strokeWidth={(mine ? 1.8 : 1.1) * u} strokeDasharray={p.preparing ? `${3 * u} ${4 * u}` : undefined} />
                  {level === 'far'
                    ? <text className={g.planetCount} y={r * 0.35} fontSize={Math.max(10 * u, r * 0.95)} textAnchor="middle">{p.songs.length}</text>
                    : <text className={g.planetInitial} y={r * 0.33} fontSize={r * 0.9} textAnchor="middle">{Array.from(p.name)[0]}</text>}
                  {labels.has(p.artistId) && (
                    <text className={g.planetName} y={r + 13 * u} fontSize={11 * u} textAnchor="middle">{p.name}</text>
                  )}
                  {level !== 'far' && <PeopleDots ids={p.people.memberIds} members={members} r={r} u={u} />}
                  {p.people.shared > 0 && level !== 'far' && (
                    <g transform={`translate(${r * 0.78} ${-r * 0.78})`}>
                      <circle r={7 * u} fill="#FFE3B8" stroke="#141225" strokeWidth={u} />
                      <text y={3 * u} fontSize={9 * u} fontWeight={700} textAnchor="middle" fill="#3B2A10">{p.people.shared}</text>
                    </g>
                  )}
                </g>
              )
            })}
          </g>
        </svg>
      </div>
      <div className={g.controls}>
        <button type="button" className={g.ctrl} aria-label="확대" onClick={() => zoomBy(1.4)}>+</button>
        <button type="button" className={g.ctrl} aria-label="축소" onClick={() => zoomBy(1 / 1.4)}>−</button>
        <button type="button" className={g.ctrl} aria-label="우리 구역으로 돌아가기" onClick={refit}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3" /></svg>
        </button>
      </div>
    </div>
  )
}

/** 9-1: 행성 위 사람 색 점 (9-2에서 궤도·위성으로 바뀐다) */
function PeopleDots({ ids, members, r, u }: { ids: number[]; members: Map<number, MemberView>; r: number; u: number }) {
  const step = 8 * u
  const start = -((ids.length - 1) * step) / 2
  return (
    <g aria-hidden="true" transform={`translate(0 ${-r - 6 * u})`}>
      {ids.map((id, i) => <circle key={id} cx={start + i * step} r={3 * u} fill={memberColor(members.get(id)?.colorIdx ?? 0).bg} />)}
    </g>
  )
}
