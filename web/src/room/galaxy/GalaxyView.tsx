import { useState } from 'react'
import type { MemberView, RoomMap } from '../roomApi'
import { memberColor, SHARED_GOLD } from '../roomApi'
import { GalaxyMap } from './GalaxyMap'
import type { Fresh } from './fresh'
import g from './galaxy.module.css'
import { GalaxyList } from './GalaxyList'
import s from '../room.module.css'

const focusName = (members: MemberView[], id: number | null) => {
  const m = id != null ? members.find(x => x.id === id) : undefined
  return m ? `지도에 ${m.nickname}님이 담은 노래만 밝게 보여요` : ''
}

/**
 * 지도 자리 (room-web.md Q1·9단계). 기본은 SVG 은하 지도(GalaxyMap), "목록으로 보기"를 누르면
 * 구역별 가수 목록(화면 읽기 프로그램·키보드로 훑기 쉬운 대체 화면).
 */
export interface GalaxyViewProps {
  map: RoomMap
  roomId: string
  meId: number
  onSelectArtist?: (artistId: number) => void
  /** 근처 가수(유령 행성) → 가수 화면 (I-40) */
  onSelectGhost?: (artistId: number, name: string) => void
  onAddFirst?: () => void
  /** 지도를 마지막으로 본 뒤 새로 생긴 것 (연출용, useFreshMarks) */
  fresh?: Fresh
  /** PC 배치: 부모 칸을 꽉 채우고, 보기 전환은 지도 위에, 아래에 색 안내 */
  fill?: boolean
  /** PC: 오른쪽 판에 자세히 열린 행성 */
  selectedId?: number | null
  /** I-26 PC: 이 사람 노래만 밝게 (참가자 탭·색 안내가 같은 상태를 쓴다) */
  focusId?: number | null
  /** 색 안내에서 사람을 눌렀을 때 (켜기·바꾸기·끄기는 부모가 정한다) */
  onFocus?: (memberId: number) => void
  /** 지도 빈 곳을 눌렀을 때 */
  onClearFocus?: () => void
  /** 목록으로 보기 (머리 ⋯ 메뉴·건너뛰기 링크가 같이 쓴다). 없으면 이 안에서만 */
  asList?: boolean
  onAsList?: (v: boolean) => void
}

export function GalaxyView({ map, roomId, meId, onSelectArtist, onSelectGhost, onAddFirst, fresh, fill = false, selectedId = null, focusId = null, onFocus, onClearFocus, asList: asListProp, onAsList }: GalaxyViewProps) {
  const [asListOwn, setAsListOwn] = useState(false)
  const asList = asListProp ?? asListOwn
  const setAsList = (v: boolean) => (onAsList ? onAsList(v) : setAsListOwn(v))
  if (map.planets.length === 0) return <div className={fill ? g.fillCenter : undefined}><EmptyGalaxy onAddFirst={onAddFirst} /></div>
  // 지도 위 "목록으로 보기" 칩은 없앴다(11-14): 페이지 첫 Tab의 건너뛰기 링크(RoomShell) + 머리 ⋯ 메뉴
  if (!asList) {
    return (
      <section aria-label="우리 음악 은하" className={fill ? g.fillMap : undefined} style={fill ? undefined : { display: 'flex', flexDirection: 'column', gap: 8 }}>
        {/* PC: 지도 위에 얹힌 유리 띠 자리(맞춤·구역 이름이 피한다) */}
        {fill && <div data-map-overlay="top" aria-hidden="true" style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 68, pointerEvents: 'none' }} />}
        <GalaxyMap map={map} meId={meId} roomId={roomId} onSelectArtist={onSelectArtist} onSelectGhost={onSelectGhost} fresh={fresh} selectedId={selectedId}
          focusId={focusId} onEmptyTap={focusId != null ? onClearFocus : undefined} />
        {fill && <Legend members={map.members} hasGhosts={map.ghosts.length > 0} focusId={focusId} onFocus={onFocus} />}
        <span className={s.srOnly} role="status">{focusName(map.members, focusId)}</span>
      </section>
    )
  }

  return <GalaxyList map={map} roomId={roomId} meId={meId} onSelectArtist={onSelectArtist} onBack={() => setAsList(false)} fill={fill} />
}


/** 범례 그림: 실제 지도처럼 작은 행성 + 궤도 위 위성 점(사람 색·금빛), 비슷한 아티스트는 흐린 행성 */
function Mini({ dot, ghost }: { dot?: string; ghost?: boolean }) {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true" style={{ flex: 'none' }}>
      {ghost
        ? <circle cx="9" cy="9" r="5" fill="rgba(156,148,208,.28)" stroke="rgba(230,220,255,.35)" />
        : <>
          <ellipse cx="9" cy="9" rx="7.5" ry="3.6" fill="none" stroke="rgba(230,220,255,.35)" transform="rotate(-20 9 9)" />
          <circle cx="9" cy="9" r="3.2" fill="#3a3270" />
          <circle cx="15.6" cy="6.6" r="2.2" fill={dot} />
        </>}
    </svg>
  )
}

/**
 * PC 지도 아래 색 안내 (캔버스 4-PC, 11-14 유리 알약): 사람(그 색 위성) · 같이 담은 노래(금빛 위성) · 근처 추천 가수(흐린 행성).
 * I-26: 사람을 누르면 그 사람 노래만 밝게. 접을 수 있다(이 브라우저에 기억)
 */
function Legend({ members, hasGhosts, focusId, onFocus }: { members: MemberView[]; hasGhosts: boolean; focusId: number | null; onFocus?: (id: number) => void }) {
  const [closed, setClosed] = useState(() => { try { return localStorage.getItem('gyeol.legendCollapsed') === '1' } catch { return false } })
  const toggle = () => setClosed(v => { try { localStorage.setItem('gyeol.legendCollapsed', v ? '0' : '1') } catch { /* 기억 못 해도 된다 */ } return !v })
  const dim = focusId != null
  if (closed) {
    return (
      <div data-map-overlay="bottom" className={`${g.legend} ${g.legendClosed}`}>
        <button type="button" className={g.legendToggle} aria-expanded={false} onClick={toggle}><Mini dot={SHARED_GOLD} />색 안내</button>
      </div>
    )
  }
  return (
    <div data-map-overlay="bottom" className={g.legend} role={onFocus ? 'group' : undefined} aria-label={onFocus ? '사람 색 안내: 누르면 그 사람 노래만 밝게' : undefined}>
      {members.map(m => {
        const dot = <Mini dot={memberColor(m.colorIdx).bg} />
        if (!onFocus) return <span key={m.id}>{dot}{m.nickname}</span>
        return (
          <button key={m.id} type="button" className={g.legendPerson} aria-pressed={focusId === m.id}
            style={{ ['--legend-c' as string]: memberColor(m.colorIdx).bg }} onClick={() => onFocus(m.id)}>
            {dot}{m.nickname}
          </button>
        )
      })}
      <span className={dim ? g.legendDim : undefined}><Mini dot={SHARED_GOLD} />같이 담은 노래</span>
      {hasGhosts && <span className={dim ? g.legendDim : undefined}><Mini ghost />비슷한 아티스트</span>}
      <button type="button" className={g.legendToggle} aria-expanded={true} aria-label="색 안내 접기" onClick={toggle}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true"><path d="M6 15l6-6 6 6" /></svg>
      </button>
    </div>
  )
}

function EmptyGalaxy({ onAddFirst }: { onAddFirst?: () => void }) {
  return (
    <section className={g.empty}>
      <svg width="120" height="120" viewBox="0 0 120 120" aria-hidden="true">
        <circle cx="60" cy="60" r="44" fill="none" stroke="#3A3466" strokeDasharray="2 6" strokeLinecap="round" />
        <circle cx="60" cy="60" r="18" fill="#2E2A55" stroke="#5E5794" />
        <path d="M92 30l1.6 5.4 5.4 1.6-5.4 1.6-1.6 5.4-1.6-5.4-5.4-1.6 5.4-1.6z" fill="#FFE3B8" />
      </svg>
      <h2>아직 빈 우주예요</h2>
      <p>좋아하는 노래를 담으면 가수가 행성으로 떠요.<br />첫 노래를 담아 볼까요?</p>
      {onAddFirst && <button type="button" className={g.cta} onClick={onAddFirst}>노래 찾아 담기</button>}
    </section>
  )
}
