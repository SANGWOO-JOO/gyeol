import { useState } from 'react'
import type { MemberView, Planet, RoomMap } from '../roomApi'
import { memberColor } from '../roomApi'
import { groupByGalaxy, planetPeople } from '../roomLogic'
import { GalaxyMap } from './GalaxyMap'
import g from './galaxy.module.css'

/**
 * 지도 자리 (room-web.md Q1·9단계). 기본은 SVG 은하 지도(GalaxyMap), "목록으로 보기"를 누르면
 * 구역별 가수 목록(화면 읽기 프로그램·키보드로 훑기 쉬운 대체 화면).
 */
export interface GalaxyViewProps {
  map: RoomMap
  roomId: string
  meId: number
  onSelectArtist?: (artistId: number) => void
  onAddFirst?: () => void
}

export function GalaxyView({ map, roomId, meId, onSelectArtist, onAddFirst }: GalaxyViewProps) {
  const [asList, setAsList] = useState(false)
  if (map.planets.length === 0) return <EmptyGalaxy onAddFirst={onAddFirst} />
  const toggle = <button type="button" className={g.viewToggle} onClick={() => setAsList(v => !v)}>{asList ? '지도로 보기' : '목록으로 보기'}</button>
  if (!asList) {
    return (
      <section aria-label="우리 음악 은하" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {toggle}
        <GalaxyMap map={map} meId={meId} roomId={roomId} onSelectArtist={onSelectArtist} />
      </section>
    )
  }

  const members = new Map(map.members.map(m => [m.id, m]))
  return (
    <section className={g.board} aria-label="우리 우주의 가수 목록 (임시 지도)">
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
