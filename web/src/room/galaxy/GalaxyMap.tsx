import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import type { MemberView, RoomMap } from '../roomApi'
import { memberColor } from '../roomApi'
import { planetPeople } from '../roomLogic'
import { fitView, GALAXY_PX, orbitSpin, screenSpacing, labelBox, NAME_PX, pan, screenPlanetRadius, stars, toWorld, visibleLabels, zoomAt, zoomGrow, zoomLevel, type View } from './galaxyMath'
import { NO_FRESH, songKey, type Fresh } from './fresh'
import { orbitLayout, ringRadius, satelliteRadius, type Ring } from './orbits'
import g from './galaxy.module.css'

/**
 * 은하 지도 (room-plan 9절 ⑤⑦⑧⑨⑫, room-web.md 9단계). 방에 담긴 가수의 행성만, 공용 지도 좌표 그대로.
 * 확대·이동 중에는 React를 다시 그리지 않고 그룹 변환만 바꾸고, 멈춘 뒤(0.15초) 그 배율에 맞춰
 * 행성·글자를 화면에서 읽을 수 있는 크기로 다시 그린다. 이름은 겹치지 않는 것만 보인다.
 */
export function GalaxyMap({ map, meId, roomId, onSelectArtist, onSelectGhost, fresh = NO_FRESH, selectedId = null }: {
  map: RoomMap; meId: number; roomId: string; onSelectArtist?: (artistId: number) => void; fresh?: Fresh
  /** 근처 가수(유령 행성)를 눌렀을 때 → 가수 화면 (I-40). 없으면 onSelectArtist */
  onSelectGhost?: (artistId: number, name: string) => void
  /** PC: 오른쪽 판에 자세히 열린 행성 */
  selectedId?: number | null
}) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const layerRef = useRef<SVGGElement>(null)
  const viewRef = useRef<View>({ scale: 1, tx: 0, ty: 0 })
  const [settled, setSettled] = useState<View>(viewRef.current)
  const settleTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const [size, setSize] = useState({ width: 0, height: 0 })
  const fitted = useRef(false)
  // 처음 맞추기 전 한 프레임(배율 1)이 보이지 않게
  const [ready, setReady] = useState(false)

  const members = useMemo(() => new Map<number, MemberView>(map.members.map(m => [m.id, m])), [map.members])
  // 궤도 순서(규칙 ⑫)는 지도 데이터가 바뀔 때만 다시 정한다
  const planets = useMemo(() => {
    const order = map.members.map(m => m.id)
    return map.planets.map(p => ({ ...p, wx: toWorld(p.x), wy: toWorld(p.y), people: planetPeople(p), rings: orbitLayout(p, order) }))
  }, [map.planets, map.members])
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

  // 근처 가수 안내 말풍선 (I-40): 처음 한 번 + 마우스 올림·포커스
  const [hint, setHint] = useState<{ id: number; auto: boolean } | null>(null)
  // 처음 맞춘 배율: 확대 체감(I-37)의 기준
  const [fitScale, setFitScale] = useState(0)
  const fit = useCallback(() => {
    if (size.width === 0) return
    const v = fitView(planets.map(p => ({ x: p.wx, y: p.wy })), size)
    setFitScale(v.scale)
    apply(v, true)
  }, [apply, planets, size])

  // 공전(I-38)은 지도를 끌거나 확대하는 동안 멈춘다: React를 다시 그리지 않고 class만 바꾼다
  const resumeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const holdSpin = useCallback((resumeAfter?: number) => {
    const el = wrapRef.current
    if (!el) return
    el.classList.add(g.spinPaused)
    clearTimeout(resumeTimer.current)
    if (resumeAfter != null) resumeTimer.current = setTimeout(() => el.classList.remove(g.spinPaused), resumeAfter)
  }, [])
  useEffect(() => () => clearTimeout(resumeTimer.current), [])

  useLayoutEffect(() => {
    const el = wrapRef.current
    if (!el) return
    // 첫 크기는 바로 잰다: ResizeObserver 첫 알림은 화면 그리기 주기에 묶여 숨겨진 창·백그라운드 탭에선 늦을 수 있음
    const rect = el.getBoundingClientRect()
    if (rect.width > 0) setSize({ width: rect.width, height: rect.height })
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
    setReady(true)
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
    try { wrapRef.current?.setPointerCapture(e.pointerId) } catch { /* 이미 끝난 포인터: 캡처 없이 진행 */ }
    pointers.current.set(e.pointerId, local(e))
    if (pointers.current.size === 1) gesture.current = { moved: 0, downTarget: e.target, pinch: null }
    holdSpin()
    setHint(null)
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
    if (pointers.current.size === 0) holdSpin(300)
    // 거의 움직이지 않았으면 탭: 누른 곳의 행성·유령 행성을 연다
    if (wasSingle && gesture.current.moved < 8) {
      const hit = (gesture.current.downTarget as Element | null)?.closest?.('[data-artist]')
      const id = hit ? Number(hit.getAttribute('data-artist')) : NaN
      if (!Number.isNaN(id)) select(id)
    }
  }

  useEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      touched.current = true
      holdSpin(400)
      setHint(null)
      const p = local(e)
      apply(zoomAt(viewRef.current, p.x, p.y, Math.exp(-e.deltaY * 0.0015)))
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [apply, holdSpin])

  const zoomBy = (f: number) => { touched.current = true; apply(zoomAt(viewRef.current, size.width / 2, size.height / 2, f), true) }
  /** 행성은 자세히 판, 유령(근처 가수)은 가수 화면 */
  function select(id: number) {
    const ghost = onSelectGhost ? ghosts.find(x => x.artistId === id) : undefined
    if (ghost) onSelectGhost!(ghost.artistId, ghost.name)
    else onSelectArtist?.(id)
  }
  const onKey = (id: number) => (e: KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); select(id) } }

  // ── 멈춘 배율 기준 크기·이름 ──
  const s = settled.scale
  const u = 1 / s // 화면 1px = 세계 u
  const grow = zoomGrow(s, fitScale) // 확대 체감: 처음 맞춤보다 확대하면 1 → GROW_MAX
  const galaxyGrow = Math.min(1.3, grow) // 구역 이름은 덜 커지게
  const nameGrow = Math.min(1.4, grow) // 행성 이름도 행성보다 덜
  const ghostR = screenPlanetRadius(1, s) * grow // 근처 가수: 노래 1곡 행성 크기
  const spacing = useMemo(() => screenSpacing(planets.map(p => ({ x: p.wx, y: p.wy })), settled, size), [planets, settled, size])
  const level = zoomLevel(s, spacing)
  const radius = useMemo(() => new Map(planets.map(p => [p.artistId, screenPlanetRadius(p.songs.length, s) * grow])), [planets, s, grow])
  // 궤도까지 포함한 바깥 반지름(px): 멀리서는 궤도를 그리지 않으므로 행성만
  const outer = useMemo(() => new Map(planets.map(p => {
    const r = radius.get(p.artistId)!
    return [p.artistId, level === 'far' || p.rings.length === 0 ? r : ringRadius(r, p.rings.length - 1, grow) + 2 * grow]
  })), [planets, radius, level, grow])
  const nameItems = useMemo(() => planets.map(p => ({
    id: p.artistId, sx: p.wx * s + settled.tx, sy: p.wy * s + settled.ty, r: outer.get(p.artistId)!,
    w: Math.min(130 * nameGrow, (Array.from(p.name).length * 9 + 8) * nameGrow), weight: p.songs.length + (p.people.memberIds.includes(meId) ? 0.5 : 0),
  })), [planets, s, settled.tx, settled.ty, outer, meId, nameGrow])
  // 화면 밖(한 화면 거리 이상) 행성은 그리지 않는다: 수백 개여도 가볍게. 이동 중엔 이 여유 안에서 보인다
  const onScreen = useMemo(() => {
    const mx = size.width, my = size.height
    return new Set(nameItems.filter(it => it.sx > -mx - it.r && it.sx < size.width + mx + it.r && it.sy > -my - it.r && it.sy < size.height + my + it.r).map(it => it.id))
  }, [nameItems, size])
  // 공전은 실제로 화면 안(여유 없이)에 있는 행성만: 여유 칸 행성까지 돌리면 수백 개 지도에서 애니메이션이 몇 배로 는다
  const inView = useMemo(() => new Set(nameItems.filter(it => it.sx > -it.r && it.sx < size.width + it.r && it.sy > -it.r && it.sy < size.height + it.r).map(it => it.id)), [nameItems, size])
  // 이름 자리 정하기: 행성 몸체를 먼저 놓고 → 행성 이름(노래 많은 순) → 유령 이름 → 구역 이름. 겹치면 숨긴다
  const { labels, ghostLabels, galaxyLabels } = useMemo(() => {
    const bodies = nameItems.map(it => ({ x0: it.sx - it.r - 3, x1: it.sx + it.r + 3, y0: it.sy - it.r - 3, y1: it.sy + it.r }))
    const nameH = 15 * nameGrow
    const labels = level === 'far' ? new Set<number>() : visibleLabels(nameItems, nameH, bodies)
    const taken = [...bodies, ...nameItems.filter(it => labels.has(it.id)).map(it => labelBox(it, nameH))]
    let ghostLabels = new Set<number>()
    if (level !== 'far') {
      // 유령 몸체를 먼저 장애물로 (개수가 늘어도 이름끼리·행성과 겹치면 숨긴다)
      const gItems = ghosts.map(x => ({ id: x.artistId, sx: x.wx * s + settled.tx, sy: x.wy * s + settled.ty, r: ghostR, w: Math.min(130 * nameGrow, (Array.from(x.name).length * 9 + 8) * nameGrow), weight: 0 }))
      const gBodies = gItems.map(it => ({ x0: it.sx - it.r - 2, x1: it.sx + it.r + 2, y0: it.sy - it.r - 2, y1: it.sy + it.r + 2 }))
      ghostLabels = visibleLabels(gItems, nameH, [...taken, ...gBodies])
      taken.push(...gBodies, ...gItems.filter(it => ghostLabels.has(it.id)).map(it => labelBox(it, nameH)))
    }
    const count = new Map<number, number>()
    planets.forEach(p => { if (p.galaxyId != null) count.set(p.galaxyId, (count.get(p.galaxyId) ?? 0) + p.songs.length) })
    // 화면 가장자리에 잘리는 구역 이름은 숨긴다
    const galaxyLabels = visibleLabels(galaxies.map(x => ({
      id: x.id, sx: x.wx * s + settled.tx, sy: x.wy * s + settled.ty - 38, r: 0,
      w: (Array.from(x.name ?? '').length * 13 + 8) * galaxyGrow, weight: count.get(x.id) ?? 0,
    })).filter(it => it.sx - it.w / 2 >= 4 && it.sx + it.w / 2 <= size.width - 4 && it.sy >= 0 && it.sy + 20 * galaxyGrow <= size.height), 18 * galaxyGrow, level === 'far' ? [] : taken) // 멀리서는 구역 이름이 행성 위에 (테두리로 읽히게)
    return { labels, ghostLabels, galaxyLabels }
  }, [nameItems, ghosts, galaxies, planets, s, settled.tx, settled.ty, level, size, nameGrow, galaxyGrow, ghostR])

  // 처음 근처 가수가 화면에 보이면 방마다 한 번 안내를 띄운다 (이름이 겹쳐 숨었어도). 4.5초 뒤 저절로 닫힘
  const firstGhost = useMemo(() => {
    if (!ready || level === 'far') return null
    const pad = ghostR + 24
    return ghosts.find(x => {
      const sx = x.wx * s + settled.tx, sy = x.wy * s + settled.ty
      return sx > pad && sx < size.width - pad && sy > pad + 40 && sy < size.height - pad
    }) ?? null
  }, [ready, level, ghosts, s, settled.tx, settled.ty, size, ghostR])
  useEffect(() => {
    if (!firstGhost) return
    const k = `gyeol.ghostHint.${roomId}`
    try { if (localStorage.getItem(k)) return; localStorage.setItem(k, '1') } catch { return }
    setHint({ id: firstGhost.artistId, auto: true })
  }, [firstGhost, roomId])
  useEffect(() => {
    if (!hint?.auto) return
    const t = setTimeout(() => setHint(h => (h?.auto ? null : h)), 4500)
    return () => clearTimeout(t)
  }, [hint])
  const hintGhost = hint ? ghosts.find(x => x.artistId === hint.id) : undefined

  const v = viewRef.current
  return (
    <div className={g.mapWrap}>
      <div ref={wrapRef} className={g.map} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}>
        <svg width="100%" height="100%" style={{ opacity: ready ? 1 : 0 }} role="group" aria-label={`우리 음악 은하. 행성 ${planets.length}개. 행성을 누르면 자세히 보여요`}>
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
            {/* ⑧·I-40 근처 가수(아직 우리 우주에 없음): 흐린 원 + "+" — 준비 중(점선 고리)·실제 행성과 구분 */}
            {level !== 'far' && ghosts.map(x => (
              <g key={`gh${x.artistId}`} className={g.ghost} data-artist={x.artistId} transform={`translate(${x.wx} ${x.wy})`}
                role="button" tabIndex={0} aria-label={`${x.name}: 아직 우리 우주에 없는 비슷한 가수. 누르면 노래 찾기`} onKeyDown={onKey(x.artistId)}
                onPointerEnter={e => { if (e.pointerType === 'mouse') setHint({ id: x.artistId, auto: false }) }}
                onPointerLeave={() => setHint(h => (h?.id === x.artistId ? null : h))}
                onFocus={() => setHint({ id: x.artistId, auto: false })} onBlur={() => setHint(null)}>
                <circle className={g.ghostHit} r={Math.max(20, ghostR + 8) * u} />
                <circle className={g.ghostBody} r={ghostR * u} strokeWidth={1.2 * u} />
                <path className={g.ghostPlus} d={`M${-ghostR * 0.45 * u} 0H${ghostR * 0.45 * u}M0 ${-ghostR * 0.45 * u}V${ghostR * 0.45 * u}`} strokeWidth={1.6 * u} />
                {ghostLabels.has(x.artistId) && <text y={(ghostR + 13 * nameGrow) * u} fontSize={NAME_PX * nameGrow * u} textAnchor="middle">{x.name}</text>}
              </g>
            ))}
            {planets.filter(p => onScreen.has(p.artistId)).map(p => {
              const r = radius.get(p.artistId)! * u
              const ro = outer.get(p.artistId)! * u
              const mine = p.people.memberIds.includes(meId)
              const who = p.people.memberIds.map(id => members.get(id)?.nickname).filter(Boolean).join(', ')
              // 연출: 4-f 첫 행성 · 새 행성(또는 준비 끝) · 멀리서 새 위성이 생긴 행성은 물결로 알림
              const born = fresh.planets.has(p.artistId)
              const ping = !born && level === 'far' && p.songs.some(x => fresh.tracks.has(songKey(p.artistId, x.trackId)) || fresh.gold.has(songKey(p.artistId, x.trackId)))
              return (
                <g key={p.artistId} className={`${g.planetNode} ${p.preparing ? g.preparingNode : ''} ${selectedId === p.artistId ? g.selectedNode : ''}`} data-artist={p.artistId} aria-current={selectedId === p.artistId ? 'true' : undefined}
                  transform={`translate(${p.wx} ${p.wy})`} role="button" tabIndex={0} onKeyDown={onKey(p.artistId)}
                  aria-label={`${p.name}, 노래 ${p.songs.length}곡${who ? `, ${who}` : ''}${p.people.shared ? `, 같이 담은 노래 ${p.people.shared}곡` : ''}${p.preparing ? ', 우주에 올리는 중' : ''}`}>
                  <circle className={g.planetHit} r={Math.max(ro, r + 10 * u)} />
                  {selectedId === p.artistId && <circle className={g.selectRing} r={r + 4 * u} strokeWidth={2 * u} />}
                  {(born || ping) && <Ripples r={born ? ro : r} u={u} first={born && fresh.first} />}
                  {p.preparing && <circle className={g.prepRing} r={r + 5 * u} strokeWidth={1.2 * u} strokeDasharray={`${2 * u} ${5 * u}`} />}
                  <g className={born ? (fresh.first ? g.firstPlanet : g.bornPlanet) : undefined}>
                  {level !== 'far' && <Orbits artistId={p.artistId} rings={p.rings} r={radius.get(p.artistId)!} u={u} grow={grow} members={members} near={level === 'near'} fresh={fresh} spin={inView.has(p.artistId)} />}
                  <circle className={g.planetBody} r={r} fill={p.preparing ? 'none' : 'url(#gl-planet)'} stroke={mine ? '#E6DCFF' : '#5E5794'}
                    strokeWidth={(mine ? 1.8 : 1.1) * u} strokeDasharray={p.preparing ? `${3 * u} ${4 * u}` : undefined} />
                  {level === 'far'
                    ? <text className={g.planetCount} y={r * 0.35} fontSize={Math.max(10 * u, r * 0.95)} textAnchor="middle">{p.songs.length}</text>
                    : <text className={g.planetInitial} y={r * 0.33} fontSize={r * 0.9} textAnchor="middle">{Array.from(p.name)[0]}</text>}
                  {labels.has(p.artistId) && (
                    <text className={g.planetName} y={ro + 13 * nameGrow * u} fontSize={NAME_PX * nameGrow * u} textAnchor="middle">{p.name}</text>
                  )}
                  {p.people.shared > 0 && level !== 'far' && (
                    <g transform={`translate(${r * 0.78} ${-r * 0.78})`}>
                      <circle r={7 * grow * u} fill="#FFE3B8" stroke="#141225" strokeWidth={u} />
                      <text y={3 * grow * u} fontSize={9 * grow * u} fontWeight={700} textAnchor="middle" fill="#3B2A10">{p.people.shared}</text>
                    </g>
                  )}
                  </g>
                </g>
              )
            })}
            {/* 구역 이름은 맨 위에 (멀리서는 행성 위에 겹쳐 그림) */}
            <g aria-hidden="true" className={g.galaxyNames}>
              {galaxies.filter(x => galaxyLabels.has(x.id)).map(x => <text key={x.id} x={x.wx} y={x.wy - 22 * u} fontSize={GALAXY_PX * galaxyGrow * u} strokeWidth={3 * u} textAnchor="middle">{x.name}</text>)}
            </g>
          </g>
        </svg>
        {hintGhost && level !== 'far' && (
          <div className={g.ghostTip} role="tooltip" style={{ left: hintGhost.wx * s + settled.tx, top: hintGhost.wy * s + settled.ty - ghostR - 8 }}>
            <strong>{hintGhost.name}</strong><span>아직 우리 우주에 없는 비슷한 가수</span><span>누르면 노래 찾기</span>
          </div>
        )}
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

/**
 * 궤도·위성 (규칙 ⑫). 크기는 화면 px로 계산하고 u(세계/px)를 곱해 그린다.
 * 금빛 궤도 = 같이 담은 노래, 사람 궤도 = 그 사람 색 위성(혼자 담은 노래). 가까이(near)에선 위성을 조금 크게.
 */
function Orbits({ artistId, rings, r, u, grow, members, near, fresh, spin }: {
  artistId: number; rings: Ring[]; r: number; u: number; grow: number; members: Map<number, MemberView>; near: boolean; fresh: Fresh
  /** 화면 안이면 돈다 */
  spin: boolean
}) {
  const color = (id: number) => memberColor(members.get(id)?.colorIdx ?? 0).bg
  const k = (near ? 1.3 : 1) * grow
  return (
    <g aria-hidden="true" className={g.orbits}>
      {rings.map((ring, i) => {
        const rr = ringRadius(r, i, grow) * u
        const gold = ring.kind === 'gold'
        const flash = gold && ring.sats.some(s => fresh.gold.has(songKey(artistId, s.trackId)))
        return (
          <SpinRing key={i} artistId={artistId} ring={i} guard={rr + 8 * k * u} on={spin}>
            <circle r={rr} fill="none" className={`${gold ? g.goldRing : g.personRing} ${flash ? g.goldFlash : ''}`}
              stroke={gold ? undefined : color(ring.memberIds[0])} strokeWidth={(gold ? 1 : 0.8) * u} />
            {ring.sats.map(sat => {
              const key = songKey(artistId, sat.trackId)
              const cx = Math.cos(sat.angle) * rr, cy = Math.sin(sat.angle) * rr
              const sr = satelliteRadius(sat, ring.kind) * k * u
              return (
                <g key={sat.trackId}>
                  {/* 4-e 새 위성: 튀어나오듯 / 4-h 겹친 순간: 금빛 반짝임 */}
                  <circle cx={cx} cy={cy} r={sr} fill={gold ? undefined : color(sat.memberIds[0])}
                    className={`${gold ? g.goldSat : ''} ${fresh.tracks.has(key) || fresh.gold.has(key) ? g.satBorn : ''}`} />
                  {fresh.gold.has(key) && <Sparkle x={cx} y={cy} u={u} />}
                </g>
              )
            })}
          </SpinRing>
        )
      })}
    </g>
  )
}

/**
 * 공전하는 궤도 하나 (I-38): 궤도(링) 단위 g 하나를 CSS로 돌린다 → 애니메이션 수 = 화면 안 궤도 수, 위성 수와 무관.
 * 시작 위상은 벽시계 기준(음수 delay)이라 모든 행성이 같은 시계로 돈다.
 * 회전 중심이 행성 중심이 되게 fill-box의 대칭을 보이지 않는 보호 원(guard)으로 맞춘다.
 * 멈춤: 지도 조작 중(.spinPaused) · 행성에 마우스를 올렸을 때 · 움직임 줄이기 설정. 위성 하나를 멈추는 기능이 생기면 같은 class를 쓴다.
 */
function SpinRing({ artistId, ring, guard, on, children }: { artistId: number; ring: number; guard: number; on: boolean; children: ReactNode }) {
  const { seconds, reverse } = orbitSpin(artistId, ring)
  // 벽시계 위상: 돌기 시작할 때(화면에 들어올 때) 한 번 정하고, 다시 그려도 바꾸지 않는다(바꾸면 튄다)
  const delay = useMemo(() => -((Date.now() / 1000) % seconds), [on, seconds]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <g className={on ? g.spin : g.spinStill} style={on ? { animationDuration: `${seconds}s`, animationDelay: `${delay}s`, animationDirection: reverse ? 'reverse' : 'normal' } : undefined}>
      <circle r={guard} fill="none" stroke="none" />
      {children}
    </g>
  )
}

/** 퍼지는 물결: 새 행성·준비 끝난 행성·멀리서 새 위성이 생긴 행성. 첫 행성(4-f)은 두 번, 더 크게 */
function Ripples({ r, u, first }: { r: number; u: number; first: boolean }) {
  return (
    <g aria-hidden="true" className={g.ripples}>
      <circle className={first ? g.rippleFirst : g.ripple} r={r + 4 * u} strokeWidth={1.5 * u} />
      {first && <circle className={`${g.rippleFirst} ${g.rippleLate}`} r={r + 4 * u} strokeWidth={1.5 * u} />}
    </g>
  )
}

/** 4-h 금빛 반짝임: 위성 둘레 네 갈래 별 셋 */
const STAR = 'M0 -1L.22 -.22 1 0 .22 .22 0 1-.22 .22-1 0-.22-.22z'
function Sparkle({ x, y, u }: { x: number; y: number; u: number }) {
  const spots = [[-9, -8, 5], [9, -6, 4], [2, 10, 3.5]]
  return (
    <g aria-hidden="true" transform={`translate(${x} ${y})`}>
      {spots.map(([dx, dy, s], i) => (
        <g key={i} transform={`translate(${dx * u} ${dy * u}) scale(${s * u})`}>
          <path d={STAR} className={g.sparkle} style={{ animationDelay: `${i * 120}ms` }} />
        </g>
      ))}
    </g>
  )
}
