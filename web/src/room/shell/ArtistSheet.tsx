import { useEffect, useState } from 'react'
import { roomApi, RoomApiError, type ArtistView, type MemberView, type RoomMap } from '../roomApi'
import { memberColor, SHARED_GOLD } from '../roomApi'
import { songOwners, sourceName } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import { Sheet } from './Sheet'
import { CreditButton } from './ArtistFace'
import { CoverImg } from './CoverImg'
import { AllSongsButton } from './AllSongsButton'
import { Avatar } from '../components/Avatar'
import { sourcesOf, useImageChain } from '../images'
import { genreColor, photoCreditText, planetFace } from '../galaxy/faces'
import { ringArcs, roomPills, roomSummary, sharedFirst } from './artistSheetLogic'
import ak from './artistSheet.module.css'
import type { ToastData } from './Toast'
import sh from './shell.module.css'

/**
 * 4′ 가수(행성)를 눌렀을 때: 방에 담긴 이 가수 노래(같이 담음 금빛, 내 노래 ✕), 근거로 쓴 설명과 출처,
 * 비슷한 아티스트(우리 우주에 없는 가수는 금빛 "함께 들어 볼 아티스트"), 노래 전체 보기(I-30 4′2 → 가수 화면).
 */
/** 사실 줄(FACTS) → 칩: 지역, 태그 (날짜는 서버가 빼서 보낸다) */
export function factChips(f: { area?: string | null; tags?: string[] | null } | null | undefined): string[] {
  return [f?.area, ...(f?.tags ?? [])].filter((x): x is string => !!x && x.trim() !== '').slice(0, 8)
}

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
  const [pillsAll, setPillsAll] = useState(false)
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
  // 알약: 사람마다 이 가수 곡 전체 수(같이 담은 곡 포함), 나 먼저·3명까지(+n). 고리는 겹치지 않는 몫(혼자 + 금빛 같이)의 비율 그림
  const pills = roomPills(songs, memberOrder, meId)
  // 우리 방 몫: 얼굴 둘레 고리 조각(사람 색, 같이 담은 몫은 금빛) + 이름 아래 숫자 알약
  const ringParts = [
    ...sum.solo.map(x => ({ color: memberColor(members.get(x.memberId)?.colorIdx ?? 0).bg, count: x.count })),
    ...(sum.shared > 0 ? [{ color: SHARED_GOLD, count: sum.shared }] : []),
  ]
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
      banner={<Banner roomId={roomId} name={name} sources={fc.kind === 'genre' ? [] : fc.sources} color={gColor} photo={fc.kind === 'photo'} credit={credit ? { text: credit, pageUrl: a?.photo?.pageUrl } : null} ring={ringParts} />}
      sub={<>
        {galaxyName && <span className={ak.genreChip} title={galaxyName}><i style={{ background: gColor }} aria-hidden="true" /><span>{galaxyName}</span></span>}
        {a && sum.total > 0 && (
          <span className={ak.pills} aria-label="우리 방에서 이 가수">
            {(pillsAll ? pills.people : pills.shown).map(x => {
              const m = members.get(x.memberId)
              return <span key={x.memberId} className={ak.pill}><i style={{ background: memberColor(m?.colorIdx ?? 0).bg }} aria-hidden="true" />{x.memberId === meId ? '나' : m?.nickname} {x.count}</span>
            })}
            {pills.hidden > 0 && (
              <button type="button" className={`${ak.pill} ${ak.pillMore}`} aria-expanded={pillsAll} onClick={() => setPillsAll(v => !v)}
                aria-label={pillsAll ? '사람 접기' : `${pills.hidden}명 더 보기`}>{pillsAll ? '접기' : `+${pills.hidden}`}</button>
            )}
            {pills.shared > 0 && <span className={`${ak.pill} ${ak.pillGold}`}><i aria-hidden="true" />같이 {pills.shared}</span>}
          </span>
        )}
      </>}
    >
      {!a && !failed && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
      {failed && <p className={sh.center} role="alert">가수 정보를 불러오지 못했어요.</p>}

      {a && (
        <>

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
            {a.descriptionKind === 'FACTS'
              ? <div className={ak.facts} aria-label="이 아티스트 정보">{factChips(a.facts).map(t => <span key={t}>{t}</span>)}</div>
              : a.description
              ? <p id={`why-${a.artistId}`} className={open ? undefined : ak.clamp}>{a.description}</p>
              : <p>{a.preparing ? '이 아티스트를 우주에 올리는 중이에요. 근거를 모아 설명을 쓰고 있어요.' : '아직 근거로 쓴 설명이 없어요.'}</p>}
            {a.description && a.descriptionKind !== 'FACTS' && (
              <button type="button" className={ak.more} aria-expanded={open} aria-controls={`why-${a.artistId}`} onClick={() => setOpen(v => !v)}>{open ? '접기' : '더 읽기'}</button>
            )}
            {(open || a.descriptionKind === 'FACTS') && a.evidence.length > 0 && (
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
                  // 얼굴: 응답 face(사진 → 저장 표지) → 지도 행성 얼굴 → 근처 가수 사진 → 장르색
                  const rf = n.face && (n.face.kind === 'PHOTO' || n.face.kind === 'COVER') && n.face.url ? sourcesOf([n.face.url]) : null
                  const src = rf ?? (nf && nf.kind !== 'genre' ? nf.sources : g?.face?.kind === 'PHOTO' && g.face.url ? sourcesOf([g.face.url]) : [])
                  // 사진이 없으면 그 가수의 장르색(구역을 알면), 모르면 방 기본 보라
                  const gid = n.galaxyId ?? f?.galaxyId ?? g?.galaxyId ?? null
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

/** 배너 뒤 우주 점 (정적, 장식) */
const STARS = [[8, 18, 1.2], [22, 72, .8], [37, 30, 1], [55, 12, .7], [63, 64, 1.3], [71, 38, .8], [84, 20, 1], [92, 58, .7], [48, 84, .9], [15, 46, .6], [78, 82, .8], [96, 32, 1.1]]

/**
 * 가수 판 배너 (11-14 3차): 흐린 그림 배경 + 옅은 별 점, 왼쪽에 원형 얼굴(지도 행성처럼 cover·위 기준).
 * 얼굴 둘레 고리 = 우리 방 몫(사람 색 조각, 같이 담은 몫은 금빛). 사진 출처 ⓘ는 원 오른쪽 아래
 */
function Banner({ roomId, name, sources, color, photo, credit, ring }: {
  roomId: string; name: string; sources: import('../images').ImgSource[]; color: string; photo: boolean
  credit: { text: string; pageUrl?: string | null } | null; ring: { color: string; count: number }[]
}) {
  const { src, fail } = useImageChain(roomId, sources)
  const [loaded, setLoaded] = useState<string | null>(null)
  const total = ring.reduce((t, x) => t + x.count, 0)
  const R = 54, C = 2 * Math.PI * R
  // 조각마다 최소 10°, 틈 3° (사람이 많아도 실처럼 가늘어지지 않게)
  const arcs = ringArcs(ring.map(x => x.count))
  const parts = ring.filter(x => x.count > 0)
  return (
    <>
      <div className={ak.banner} aria-hidden="true">
        {src
          ? <img className={ak.bannerBlur} src={src} alt="" />
          : <div className={ak.bannerBlurFallback} style={{ background: `radial-gradient(120% 120% at 20% 20%, ${color}, #241f47 75%)` }} />}
        <svg className={ak.stars} viewBox="0 0 100 100" preserveAspectRatio="none">
          {STARS.map(([x, y, r], i) => <circle key={i} cx={x} cy={y} r={r * .35} fill="#fff" opacity={.35 + (i % 3) * .15} />)}
        </svg>
        <div className={ak.bannerShade} />
      </div>
      <div className={ak.faceWrap}>
        {/* 고리: 우리 방 몫 (장식, 숫자는 이름 아래 알약이 읽힌다) */}
        {total > 0 && (
          <svg className={ak.ring} viewBox="0 0 120 120" aria-hidden="true">
            <circle cx="60" cy="60" r={R} fill="none" stroke="rgba(255,255,255,.12)" strokeWidth="5" />
            {arcs.map((a, i) => (
              <circle key={i} cx="60" cy="60" r={R} fill="none" stroke={parts[i].color} strokeWidth="5" strokeLinecap={arcs.length > 1 ? 'round' : 'butt'}
                strokeDasharray={`${Math.max(0.1, (a.sweep / 360) * C - (arcs.length > 1 ? 5 : 0))} ${C}`} strokeDashoffset={-(a.start / 360) * C} transform="rotate(-90 60 60)" />
            ))}
          </svg>
        )}
        <div className={ak.face} aria-hidden="true" style={src ? undefined : { background: `radial-gradient(120% 120% at 30% 20%, ${color}, #2e2760 80%)` }}>
          {src ? <img src={src} alt="" onLoad={() => setLoaded(src)} onError={fail} /> : <span>{Array.from(name)[0] ?? ''}</span>}
        </div>
        {photo && credit && src && loaded === src && <div className={ak.faceCredit}><CreditButton text={credit.text} pageUrl={credit.pageUrl} /></div>}
      </div>
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
