import { useEffect, useState } from 'react'
import { roomApi, RoomApiError, type ArtistView, type MemberView, type RoomMap } from '../roomApi'
import { memberColor } from '../roomApi'
import { songOwners, sourceName } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import { Sheet } from './Sheet'
import { CreditButton } from './ArtistFace'
import { CoverImg } from './CoverImg'
import { AllSongsButton } from './AllSongsButton'
import { Avatar } from '../components/Avatar'
import { sourcesOf, useImageChain } from '../images'
import { genreColor, photoCreditText, planetFace } from '../galaxy/faces'
import { roomSummary, sharedFirst } from './artistSheetLogic'
import ak from './artistSheet.module.css'
import type { ToastData } from './Toast'
import sh from './shell.module.css'

/**
 * 4′ 가수(행성)를 눌렀을 때: 방에 담긴 이 가수 노래(같이 담음 금빛, 내 노래 ✕), 근거로 쓴 설명과 출처,
 * 비슷한 아티스트(우리 우주에 없는 가수는 금빛 "함께 들어 볼 아티스트"), 노래 전체 보기(I-30 4′2 → 가수 화면).
 */
/** 비슷한 아티스트는 처음 8명, 나머지는 더 보기 */
const NEAR_MAX = 8

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
  const [open, setOpen] = useState(false)
  const [nearAll, setNearAll] = useState(false)
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
  const memberOrder = (map?.members ?? []).map(m => m.id)
  const songs = a?.songs ?? planet?.songs ?? []
  const sum = roomSummary(songs, memberOrder)
  // 담은 사람이 한 명(같이 담은 곡 없음)이면 막대 없이 글자만
  const single = sum.shared === 0 && sum.solo.length === 1 ? sum.solo[0] : null
  const gColor = genreColor(galaxyName, planet?.galaxyId ?? null)
  // 배너 그림: 판 응답의 얼굴(PHOTO 사진 → COVER) → 지도 얼굴 규칙(방 곡 표지) → 장르색 + 이니셜
  const face = a?.face ?? planet?.face ?? null
  const fc = planetFace({ songs, face })
  const credit = a?.photo && photoCreditText(a.photo)
  const ghosts = new Map((map?.ghosts ?? []).map(g => [g.artistId, g]))
  const planets = new Map((map?.planets ?? []).map(p => [p.artistId, p]))
  const galaxyNames = new Map((map?.galaxies ?? []).map(g => [g.id, g.name]))

  return (
    <Sheet
      title={name}
      onClose={onClose}
      banner={<Banner roomId={roomId} name={name} sources={fc.kind === 'genre' ? [] : fc.sources} color={gColor} photo={fc.kind === 'photo'} credit={credit ? { text: credit, pageUrl: a?.photo?.pageUrl } : null} />}
      sub={galaxyName ? <span className={ak.genreChip}><i style={{ background: gColor }} aria-hidden="true" />{galaxyName}</span> : undefined}
    >
      {!a && !failed && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
      {failed && <p className={sh.center} role="alert">가수 정보를 불러오지 못했어요.</p>}

      {a && (
        <>
          {/* 우리 방에서 이 가수: 사람 색 막대(같이 담은 곡은 금빛) + 글자 */}
          <section className={ak.summary} aria-label="우리 방에서 이 가수">
            {single ? (
              <div className={ak.summaryText}>
                <span><i style={{ background: memberColor(members.get(single.memberId)?.colorIdx ?? 0).bg }} aria-hidden="true" />{single.memberId === meId ? '나' : members.get(single.memberId)?.nickname} · <b>{single.count}</b>곡</span>
              </div>
            ) : <>
            <div className={ak.summaryBar} aria-hidden="true">
              {sum.solo.map(x => <i key={x.memberId} style={{ flex: x.count, background: memberColor(members.get(x.memberId)?.colorIdx ?? 0).bg }} />)}
              {sum.shared > 0 && <i style={{ flex: sum.shared, background: '#FFE3B8' }} />}
            </div>
            <div className={ak.summaryText}>
              {sum.solo.map(x => (
                <span key={x.memberId}><i style={{ background: memberColor(members.get(x.memberId)?.colorIdx ?? 0).bg }} aria-hidden="true" />{x.memberId === meId ? '나' : members.get(x.memberId)?.nickname} <b>{x.count}</b></span>
              ))}
              {sum.shared > 0 && <span><i style={{ background: '#FFE3B8' }} aria-hidden="true" />같이 <b>{sum.shared}</b></span>}
            </div>
            </>}
          </section>

          <section style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <h3 className={ak.sectionTitle}>담긴 노래 <span>{a.songs.length}곡 · {people.size}명</span></h3>
            <ul className={ak.songs}>
              {sharedFirst(a.songs).map(song => {
                const o = songOwners(song.memberIds, meId, id => members.get(id)?.nickname)
                return (
                  <li key={song.trackId} className={`${ak.song} ${o.gold ? ak.songGold : ''}`}>
                    <CoverImg roomId={roomId} cover={song} className={ak.thumb} />
                    <span className={ak.songText}>
                      <strong>{song.title}</strong>
                      <span className={`${ak.owners} ${o.gold ? ak.ownersGold : ''}`}>
                        {o.gold && <span aria-hidden="true">
                          {song.memberIds.slice(0, 4).map(id => { const m = members.get(id); return m ? <Avatar key={id} nickname={m.nickname} colorIdx={m.colorIdx} characterId={m.avatarCharacterId} size={18} /> : null })}
                        </span>}
                        {o.label}
                      </span>
                    </span>
                    {o.mine && mySongIds.has(song.trackId) && (
                      <button type="button" className={sh.remove} style={{ width: 36, height: 36 }} aria-label={`내 노래 ${song.title} 빼기`} onClick={() => removeMine(song.trackId, song.title)}>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
                      </button>
                    )}
                  </li>
                )
              })}
            </ul>
          </section>

          <section className={ak.why}>
            <h3 className={ak.sectionTitle}>{galaxyName ? `왜 ${galaxyName}에 있을까요` : '어떤 아티스트일까요'}</h3>
            {a.description
              ? <p id={`why-${a.artistId}`} className={open ? undefined : ak.clamp}>{a.description}</p>
              : <p>{a.preparing ? '이 아티스트를 우주에 올리는 중이에요. 근거를 모아 설명을 쓰고 있어요.' : '아직 근거로 쓴 설명이 없어요.'}</p>}
            {a.description && (
              <button type="button" className={ak.more} aria-expanded={open} aria-controls={`why-${a.artistId}`} onClick={() => setOpen(v => !v)}>{open ? '접기' : '더 읽기'}</button>
            )}
            {open && a.evidence.length > 0 && (
              <div className={ak.sources} aria-label="설명에 쓴 자료">
                {a.evidence.map(e => <a key={e.id} href={e.url} target="_blank" rel="noreferrer noopener">{sourceName(e.source)}에서 보기</a>)}
              </div>
            )}
          </section>

          {a.nearby.length > 0 && (
            <section style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <h3 className={ak.sectionTitle}>비슷한 아티스트</h3>
              <div className={ak.nearby}>
                {(nearAll ? a.nearby : a.nearby.slice(0, NEAR_MAX)).map(n => {
                  const f = n.inRoom ? planets.get(n.artistId) : undefined
                  const nf = n.inRoom && f ? planetFace(f) : null
                  const g = ghosts.get(n.artistId)
                  const src = nf && nf.kind !== 'genre' ? nf.sources : g?.face?.kind === 'PHOTO' && g.face.url ? sourcesOf([g.face.url]) : []
                  // 사진이 없으면 그 가수의 장르색(구역을 알면), 모르면 방 기본 보라
                  const gid = f?.galaxyId ?? g?.galaxyId ?? null
                  const fill = gid != null ? genreColor(galaxyNames.get(gid), gid) : '#4a4378'
                  return (
                    <button key={n.artistId} type="button" className={`${ak.near} ${n.inRoom ? '' : ak.nearGhost}`}
                      onClick={() => (n.inRoom ? onSelectArtist(n.artistId) : onSearch(n.name))}
                      aria-label={n.inRoom ? `${n.name} 가수 판 열기` : `${n.name}: 아직 우리 우주에 없는 아티스트, 노래 찾기`}>
                      <NearFace roomId={roomId} name={n.name} sources={src} fill={fill} />
                      <span>{n.name}</span>
                      {!n.inRoom && <span className={ak.notYet}>아직 없음</span>}
                    </button>
                  )
                })}
              </div>
              {a.nearby.length > NEAR_MAX && (
                <button type="button" className={ak.more} aria-expanded={nearAll} onClick={() => setNearAll(v => !v)}>{nearAll ? '접기' : `더 보기 (${a.nearby.length - NEAR_MAX})`}</button>
              )}
            </section>
          )}

          <AllSongsButton roomId={roomId} name={a.name} covers={a.songs} color={gColor}
            sub={`인기순 · 최신순 · ${a.songs.length}곡+`} ariaLabel="노래 전체 보기" onClick={() => onOpenSongs(a.artistId, a.name)} />
        </>
      )}
    </Sheet>
  )
}

/** 배너 그림: 후보를 차례로(사진 → 표지), 모두 실패하면 장르색 그라데이션 + 큰 이니셜. 아래로 어두워지는 막 */
function Banner({ roomId, name, sources, color, photo, credit }: { roomId: string; name: string; sources: import('../images').ImgSource[]; color: string; photo: boolean; credit: { text: string; pageUrl?: string | null } | null }) {
  const { src, fail } = useImageChain(roomId, sources)
  const [loaded, setLoaded] = useState<string | null>(null)
  return (
    <>
      {/* 배경: 같은 그림을 크게 흐리게(없으면 장르색), 위에 원본을 잘리지 않게(contain) 둥근 사각형으로 */}
      <div className={ak.banner} aria-hidden="true">
        {src
          ? <img className={ak.bannerBlur} src={src} alt="" />
          : <div className={ak.bannerBlurFallback} style={{ background: `radial-gradient(120% 120% at 20% 20%, ${color}, #241f47 75%)` }} />}
        <div className={ak.bannerShade} />
        <div className={ak.bannerArt} style={src ? undefined : { background: `radial-gradient(120% 120% at 30% 20%, ${color}, #2e2760 80%)` }}>
          {src ? <img src={src} alt="" onLoad={() => setLoaded(src)} onError={fail} /> : <span>{Array.from(name)[0] ?? ''}</span>}
        </div>
      </div>
      {/* 사진 출처(CC BY): 사진이 실제로 뜬 뒤에만 배너 왼쪽 위 ⓘ */}
      {photo && credit && src && loaded === src && <div className={ak.bannerCredit}><CreditButton text={credit.text} pageUrl={credit.pageUrl} /></div>}
    </>
  )
}

function NearFace({ roomId, name, sources, fill }: { roomId: string; name: string; sources: import('../images').ImgSource[]; fill: string }) {
  const { src, fail } = useImageChain(roomId, sources)
  return (
    <span className={ak.nearFace} aria-hidden="true" style={src ? undefined : { background: `radial-gradient(120% 120% at 30% 25%, ${fill}, #241f47 85%)` }}>
      {src ? <img src={src} alt="" onError={fail} style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: '50% 0' }} /> : Array.from(name)[0]}
    </span>
  )
}
