import { useState } from 'react'
import { memberColor, SHARED_GOLD, type MemberView, type Planet, type RoomMap } from '../roomApi'
import { groupByGalaxy } from '../roomLogic'
import { Avatar } from '../components/Avatar'
import { CoverImg } from '../shell/CoverImg'
import { useImageChain } from '../images'
import { genreColor, planetFace } from './faces'
import { ringArcs, roomSummary } from '../shell/artistSheetLogic'
import sh from '../shell/shell.module.css'
import l from './list.module.css'

/**
 * 지도 대신 목록 (11-14 개편): 화면 읽기·키보드로 우주를 훑는 대체 화면.
 * 방 요약 → 장르 구역 묶음(h3) → 가수 줄(누르면 가수 판, 옆 ˅로 곡 펼치기) → 곡 목록. 장식은 aria-hidden
 */
export function GalaxyList({ map, roomId, meId, onSelectArtist, onBack, fill }: {
  map: RoomMap; roomId: string; meId: number; onSelectArtist?: (id: number) => void; onBack: () => void; fill: boolean
}) {
  const members = new Map(map.members.map(m => [m.id, m]))
  const order = map.members.map(m => m.id)
  const songs = map.planets.reduce((n, p) => n + p.songs.length, 0)
  const [open, setOpen] = useState<Set<number>>(new Set())
  const toggle = (id: number) => setOpen(prev => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n })
  return (
    <section className={`${l.list} ${fill ? l.fill : ''}`} aria-labelledby="galaxy-list-title">
      <div className={l.top}>
        <button type="button" className={l.back} onClick={onBack}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="2.6" /><ellipse cx="12" cy="12" rx="9" ry="4.2" transform="rotate(-20 12 12)" /></svg>
          지도로 보기
        </button>
      </div>
      <header className={l.summary}>
        <h2 id="galaxy-list-title">우리 우주 목록</h2>
        <p>가수 {map.planets.length}명 · 노래 {songs}곡</p>
        <span className={l.people} aria-label={`함께하는 사람 ${map.members.length}명: ${map.members.map(m => m.nickname).join(', ')}`}>
          {map.members.map(m => <span key={m.id} className={l.ring} style={{ boxShadow: `0 0 0 2px ${memberColor(m.colorIdx).bg}` }} aria-hidden="true"><Avatar nickname={m.nickname} colorIdx={m.colorIdx} characterId={m.avatarCharacterId} size={26} /></span>)}
        </span>
      </header>

      {groupByGalaxy(map).map(group => {
        const color = group.id != null ? genreColor(group.name, group.id) : '#5e5794'
        const count = group.planets.reduce((n, p) => n + p.songs.length, 0)
        return (
          <section key={group.id ?? 'none'} className={l.group} aria-labelledby={`g-${group.id ?? 'none'}`} style={{ ['--g' as string]: color }}>
            <h3 id={`g-${group.id ?? 'none'}`} className={l.groupHead}>
              <i aria-hidden="true" />{group.name}<span>가수 {group.planets.length} · 노래 {count}곡</span>
            </h3>
            <ul className={l.artists}>
              {group.planets.map(p => (
                <ArtistRow key={p.artistId} roomId={roomId} planet={p} members={members} order={order} meId={meId}
                  open={open.has(p.artistId)} onToggle={() => toggle(p.artistId)} onSelect={onSelectArtist} />
              ))}
            </ul>
          </section>
        )
      })}
    </section>
  )
}

function ArtistRow({ roomId, planet: p, members, order, meId, open, onToggle, onSelect }: {
  roomId: string; planet: Planet; members: Map<number, MemberView>; order: number[]; meId: number
  open: boolean; onToggle: () => void; onSelect?: (id: number) => void
}) {
  const people = [...new Set(p.songs.flatMap(s => s.memberIds))]
  const sum = roomSummary(p.songs, order)
  const parts = [...sum.solo.map(x => ({ c: memberColor(members.get(x.memberId)?.colorIdx ?? 0).bg, n: x.count })), ...(sum.shared ? [{ c: SHARED_GOLD, n: sum.shared }] : [])]
  const who = people.map(id => (id === meId ? '나' : members.get(id)?.nickname)).filter(Boolean).join(', ')
  const listId = `songs-${p.artistId}`
  return (
    <li className={l.artist}>
      <div className={l.artistRow}>
        <button type="button" className={l.artistMain} onClick={() => onSelect?.(p.artistId)}
          aria-label={`${p.name}, 노래 ${p.songs.length}곡${who ? `, ${who}` : ''}${sum.shared ? `, 같이 담은 노래 ${sum.shared}곡` : ''}. 가수 판 열기`}>
          <MiniFace roomId={roomId} planet={p} parts={parts} />
          <span className={l.artistText}>
            <strong>{p.name}</strong>
            <span className={l.owners} aria-hidden="true">
              <span>{people.slice(0, 4).map(id => { const m = members.get(id); return m ? <Avatar key={id} nickname={m.nickname} colorIdx={m.colorIdx} characterId={m.avatarCharacterId} size={16} /> : null })}</span>
              {p.songs.length}곡{sum.shared ? <em> · 같이 {sum.shared}</em> : null}
            </span>
          </span>
        </button>
        <button type="button" className={l.expand} aria-expanded={open} aria-controls={listId} aria-label={`${p.name} 노래 ${open ? '접기' : '펼치기'}`} onClick={onToggle}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={open ? 'M6 15l6-6 6 6' : 'M6 9l6 6 6-6'} /></svg>
        </button>
      </div>
      {open && (
        <ul id={listId} className={`${sh.songList} ${l.songs}`}>
          {p.songs.map(s => {
            const shared = s.memberIds.length >= 2
            const names = s.memberIds.map(id => (id === meId ? '나' : members.get(id)?.nickname)).filter(Boolean).join(' · ')
            return (
              <li key={s.trackId} className={`${sh.row} ${shared ? sh.rowShared : ''}`}>
                <CoverImg roomId={roomId} cover={s} className={sh.cover} />
                <span className={sh.rowText}>
                  <span className={sh.songTitle}>{s.title}</span>
                  <span className={shared ? sh.faceChips : sh.songSub}>{shared ? `${names} 같이 담음` : names}</span>
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </li>
  )
}

/** 원형 얼굴 + 우리 방 몫 미니 고리 (장식) */
function MiniFace({ roomId, planet, parts }: { roomId: string; planet: Planet; parts: { c: string; n: number }[] }) {
  const fc = planetFace(planet)
  const { src, fail } = useImageChain(roomId, fc.kind === 'genre' ? [] : fc.sources)
  const R = 21, C = 2 * Math.PI * R
  const arcs = ringArcs(parts.map(x => x.n), 14, 8)
  return (
    <span className={l.face} aria-hidden="true">
      <svg viewBox="0 0 48 48" className={l.faceRing}>
        {arcs.map((a, i) => <circle key={i} cx="24" cy="24" r={R} fill="none" stroke={parts[i].c} strokeWidth="2.5" strokeLinecap="round"
          strokeDasharray={`${Math.max(.1, (a.sweep / 360) * C - (arcs.length > 1 ? 2.5 : 0))} ${C}`} strokeDashoffset={-(a.start / 360) * C} transform="rotate(-90 24 24)" />)}
      </svg>
      <span className={l.faceImg}>{src ? <img src={src} alt="" onError={fail} /> : Array.from(planet.name)[0]}</span>
    </span>
  )
}
