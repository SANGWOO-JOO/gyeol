import { useState } from 'react'
import type { MemberView, Planet, RoomMap } from '../roomApi'
import { memberColor } from '../roomApi'
import { groupByGalaxy, planetPeople } from '../roomLogic'
import { GalaxyMap } from './GalaxyMap'
import type { Fresh } from './fresh'
import g from './galaxy.module.css'
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
}

export function GalaxyView({ map, roomId, meId, onSelectArtist, onSelectGhost, onAddFirst, fresh, fill = false, selectedId = null, focusId = null, onFocus, onClearFocus }: GalaxyViewProps) {
  const [asList, setAsList] = useState(false)
  if (map.planets.length === 0) return <div className={fill ? g.fillCenter : undefined}><EmptyGalaxy onAddFirst={onAddFirst} /></div>
  const toggle = <button type="button" className={g.viewToggle} data-map-overlay="top" onClick={() => setAsList(v => !v)}>{asList ? '지도로 보기' : '목록으로 보기'}</button>
  if (!asList) {
    return (
      <section aria-label="우리 음악 은하" className={fill ? g.fillMap : undefined} style={fill ? undefined : { display: 'flex', flexDirection: 'column', gap: 8 }}>
        {toggle}
        <GalaxyMap map={map} meId={meId} roomId={roomId} onSelectArtist={onSelectArtist} onSelectGhost={onSelectGhost} fresh={fresh} selectedId={selectedId}
          focusId={focusId} onEmptyTap={focusId != null ? onClearFocus : undefined} />
        {fill && <Legend members={map.members} hasGhosts={map.ghosts.length > 0} focusId={focusId} onFocus={onFocus} />}
        <span className={s.srOnly} role="status">{focusName(map.members, focusId)}</span>
      </section>
    )
  }

  const members = new Map(map.members.map(m => [m.id, m]))
  return (
    <section className={`${g.board} ${fill ? g.fillList : ''}`} aria-label="우리 우주의 가수 목록">
      {toggle}
      {groupByGalaxy(map).map(group => (
        <div key={group.id ?? 'none'} className={g.group}>
          <h2 className={g.galaxy}>{group.name}</h2>
          <ul className={g.planets}>
            {group.planets.map(p => (
              <PlanetRow key={p.artistId} planet={p} members={members} meId={meId} onSelect={onSelectArtist} />
            ))}
          </ul>
        </div>
      ))}
    </section>
  )
}

function PlanetRow({ planet, members, meId, onSelect }: {
  planet: Planet; members: Map<number, MemberView>; meId: number; onSelect?: (id: number) => void
}) {
  const { memberIds, shared } = planetPeople(planet)
  const mine = memberIds.includes(meId)
  const who = memberIds.map(id => members.get(id)?.nickname).filter(Boolean).join(', ')
  const label = `${planet.name}, 노래 ${planet.songs.length}곡${who ? `, ${who}` : ''}${shared ? `, 같이 담은 노래 ${shared}곡` : ''}${planet.preparing ? ', 우주에 올리는 중' : ''}`
  return (
    <li>
      <button type="button" className={`${g.planet} ${mine ? g.mine : ''}`} aria-label={label} onClick={() => onSelect?.(planet.artistId)}>
        <span className={`${g.orb} ${planet.preparing ? g.preparing : ''}`} aria-hidden="true">{Array.from(planet.name)[0]}</span>
        <span className={g.text}>
          <span className={g.name}>{planet.name}</span>
          <span className={g.sub}>{planet.preparing ? '우주에 올리는 중 · 곧 자리를 잡아요' : `노래 ${planet.songs.length}곡`}</span>
        </span>
        <span className={g.dots} aria-hidden="true">
          {memberIds.map(id => {
            const m = members.get(id)
            return <span key={id} className={g.dot} style={{ background: memberColor(m?.colorIdx ?? 0).bg }} />
          })}
          {shared > 0 && <span className={g.gold}>{shared}</span>}
        </span>
      </button>
    </li>
  )
}

/**
 * PC 지도 아래 색 안내 (캔버스 4-PC): 사람 색 · 같이 담은 노래 · 근처 추천 가수.
 * I-26: 사람을 누르면 그 사람 노래만 밝게 (참가자 탭과 같은 상태, 다시 누르면 해제)
 */
function Legend({ members, hasGhosts, focusId, onFocus }: { members: MemberView[]; hasGhosts: boolean; focusId: number | null; onFocus?: (id: number) => void }) {
  const dim = focusId != null
  return (
    <div data-map-overlay="bottom" className={g.legend} role={onFocus ? 'group' : undefined} aria-label={onFocus ? '사람 색 안내: 누르면 그 사람 노래만 밝게' : undefined} aria-hidden={onFocus ? undefined : true}>
      {members.map(m => {
        const dot = <i style={{ background: memberColor(m.colorIdx).bg }} />
        if (!onFocus) return <span key={m.id}>{dot}{m.nickname}</span>
        return (
          <button key={m.id} type="button" className={g.legendPerson} aria-pressed={focusId === m.id}
            style={{ ['--legend-c' as string]: memberColor(m.colorIdx).bg }} onClick={() => onFocus(m.id)}>
            {dot}{m.nickname}
          </button>
        )
      })}
      <span className={dim ? g.legendDim : undefined} aria-hidden="true"><i className={g.legendGold} />같이 담은 노래</span>
      {hasGhosts && <span className={dim ? g.legendDim : undefined} aria-hidden="true"><i className={g.legendGhost} />비슷한 아티스트</span>}
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
