import { useEffect, useState } from 'react'
import { roomApi, RoomApiError, type ArtistView, type MemberView, type RoomMap } from '../roomApi'
import { memberColor } from '../roomApi'
import { songOwners, sourceName } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import { Sheet } from './Sheet'
import type { ToastData } from './Toast'
import s from '../room.module.css'
import sh from './shell.module.css'

/**
 * 4′ 가수(행성)를 눌렀을 때: 방에 담긴 이 가수 노래(같이 담음 금빛, 내 노래 ✕), 근거로 쓴 설명과 출처,
 * 비슷한 아티스트(우리 우주에 없는 가수는 금빛 "함께 들어 볼 아티스트"), 노래 전체 보기(I-30 4′2 → 가수 화면).
 */
export function ArtistSheet({ roomId, artistId, map, meId, version, onClose, onSearch, onChanged, onError, show, onSelectArtist, onOpenSongs }: {
  roomId: string
  artistId: number
  map: RoomMap | null
  meId: number
  version: number
  onClose: () => void
  onSearch: (q: string) => void
  onChanged: () => void
  onError: (err: unknown) => boolean
  show: (t: Omit<ToastData, 'id'>) => void
  onSelectArtist: (id: number) => void
  /** 이 가수의 노래 전체(가수 화면)로 */
  onOpenSongs: (artistId: number, name: string) => void
}) {
  const [a, setA] = useState<ArtistView | null>(null)
  const [failed, setFailed] = useState(false)
  // 내 노래 ✕에 필요한 담은 곡 id (지도·가수 응답에는 trackId만 있다)
  const [mySongIds, setMySongIds] = useState<Map<number, number>>(new Map())

  useEffect(() => {
    const ac = new AbortController()
    setFailed(false)
    roomApi.artist(roomId, artistId, ac.signal)
      .then(setA)
      .catch(err => { if (!ac.signal.aborted && !onError(err)) setFailed(true) })
    roomApi.mySongs(roomId, null, 100, ac.signal)
      .then(p => setMySongIds(new Map(p.items.map(x => [x.trackId, x.id]))))
      .catch(() => { /* ✕만 안 보인다 */ })
    return () => ac.abort()
  }, [roomId, artistId, version, onError])

  const members = new Map<number, MemberView>((map?.members ?? []).map(m => [m.id, m]))
  const planet = map?.planets.find(p => p.artistId === artistId)
  const galaxyName = planet?.galaxyId != null ? map?.galaxies.find(g => g.id === planet.galaxyId)?.name ?? null : null
  const people = new Set(a?.songs.flatMap(x => x.memberIds) ?? [])

  async function removeMine(trackId: number, title: string) {
    const songId = mySongIds.get(trackId)
    if (songId == null) return
    try {
      await roomApi.removeSong(roomId, songId)
      show({ title: '노래를 뺐어요', sub: title, action: { label: '되돌리기', run: async () => { try { await roomApi.addTrack(roomId, trackId); onChanged() } catch (err) { onError(err) } } } })
      onChanged()
    } catch (err) {
      if (!onError(err)) show({ title: '빼지 못했어요', sub: err instanceof RoomApiError ? err.message : undefined, tone: 'error' })
    }
  }

  const name = a?.name ?? planet?.name ?? ''
  return (
    <Sheet
      title={name}
      onClose={onClose}
      head={<span className={sh.orbBig} aria-hidden="true">{Array.from(name)[0] ?? ''}</span>}
      sub={galaxyName ? <span className={sh.badge} style={{ fontSize: 12, padding: '2px 10px' }}>{galaxyName}</span> : undefined}
    >
      {!a && !failed && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
      {failed && <p className={sh.center} role="alert">가수 정보를 불러오지 못했어요.</p>}

      {a && (
        <>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div className={sh.sectionHead}>
              <h3>이 아티스트에 담긴 노래</h3>
              <span>{a.songs.length}곡 · {people.size}명</span>
            </div>
            <ul className={sh.list} style={{ gap: 6 }}>
              {a.songs.map(song => {
                const o = songOwners(song.memberIds, meId, id => members.get(id)?.nickname)
                const color = memberColor(members.get(song.memberIds[0])?.colorIdx ?? 0).bg
                return (
                  <li key={song.trackId} className={`${sh.songRow} ${o.gold ? sh.songRowGold : ''}`}>
                    {o.gold ? <span className={sh.goldCount} aria-hidden="true">{song.memberIds.length}</span> : <span className={sh.colorDot} style={{ background: color }} aria-hidden="true" />}
                    <span className={sh.songTitle} style={{ flex: 1, minWidth: 0, fontSize: 14 }}>{song.title}</span>
                    <span className={`${sh.who} ${o.gold ? sh.whoGold : ''}`}>{o.label}</span>
                    {o.mine && mySongIds.has(song.trackId) && (
                      <button type="button" className={sh.remove} style={{ width: 36, height: 36 }} aria-label={`내 노래 ${song.title} 빼기`} onClick={() => removeMine(song.trackId, song.title)}>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
                      </button>
                    )}
                  </li>
                )
              })}
            </ul>
          </div>

          <div className={sh.why}>
            <h3>{galaxyName ? `왜 ${galaxyName}에 있나요` : '어떤 아티스트인가요'}</h3>
            {a.description
              ? <p>{a.description}</p>
              : <p>{a.preparing ? '이 아티스트를 우주에 올리는 중이에요. 근거를 모아 설명을 쓰고 있어요.' : '아직 근거로 쓴 설명이 없어요.'}</p>}
            {a.evidence.length > 0 && (
              <div className={sh.evidence}>
                {a.evidence.map((e, i) => (
                  <a key={e.id} href={e.url} target="_blank" rel="noreferrer noopener">근거 {i + 1} · {sourceName(e.source)}</a>
                ))}
              </div>
            )}
          </div>

          {a.nearby.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div className={sh.sectionHead}><h3>비슷한 아티스트</h3></div>
              <div className={sh.nearby}>
                {a.nearby.map(n => n.inRoom
                  ? <button key={n.artistId} type="button" onClick={() => onSelectArtist(n.artistId)}>{n.name}</button>
                  : <button key={n.artistId} type="button" className={sh.nearbyGhost} onClick={() => onSearch(n.name)} aria-label={`${n.name} 노래 찾기 (함께 들어 볼 아티스트)`}>✦ {n.name}</button>)}
              </div>
              <span className={sh.center} style={{ textAlign: 'left' }}>✦ 표시는 아직 우리 우주에 없는 아티스트예요. 누르면 노래를 찾아요</span>
            </div>
          )}

          <button type="button" className={s.primary} style={{ height: 54, fontSize: 16, flex: 'none', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 4 }} onClick={() => onOpenSongs(a.artistId, a.name)}>
            노래 전체 보기
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
          </button>
        </>
      )}
    </Sheet>
  )
}
