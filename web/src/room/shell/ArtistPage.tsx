import { useCallback, useEffect, useRef, useState } from 'react'
import { roomApi, RoomApiError, type ArtistSongItem, type ArtistSort, type RoomSearchItem, type SearchArtist } from '../roomApi'
import { appendSongs, artistCardSub, artistEndText, artistSongsErrorText, asSearchItem, canLoadMore, groupByRelease, mergeArtist, releaseText, scanNotice, scanPollMs, trackKey } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import { SearchRow } from './SearchPanel'
import { ArtistFace } from './ArtistFace'
import s from '../room.module.css'
import sh from './shell.module.css'

/** 가수 화면을 연 곳: 검색의 가수 카드, 또는 지도 행성의 가수 판(back = 그 행성) */
export interface ArtistRef {
  artistId: number | null; name: string; songCountInRoom?: number | null; onMap?: boolean | null
  /** 지도 행성의 가수 판에서 왔으면 그 행성 (뒤로 = 그 판) */
  fromPlanet?: number
  /** 지도의 근처 가수(유령)에서 왔으면 true (뒤로 = 지도) */
  fromMap?: boolean
}

interface Loaded { items: ArtistSongItem[]; page: number; hasMore: boolean; scan: ArtistSongsScan }
type ArtistSongsScan = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | null
/** ③ 최신순(V27·worker): 11-14 백엔드 준비됨 */
const LATEST_READY = true

const errText = (err: unknown) => err instanceof RoomApiError ? artistSongsErrorText(err.status, err.code, err.message) : '노래를 불러오지 못했어요'

/**
 * I-30 가수 화면 (캔버스 4-m·4-m2): [인기순][최신순] 20곡씩 이어 불러오기.
 * 담기·금빛(친구가 담은 곡 → 나도 담기)·담음은 방 검색과 같다. 담는 일은 부모(RoomShell)가 한다.
 * 최신순은 DB에 있는 가수(artistId)만: 발매일을 처음 모으는 동안(scan RUNNING)은 모은 것만 보이고 몇 초마다 다시 읽는다.
 */
export function ArtistPage({ roomId, artist, roomCount, added, pending, busy, onAdd, onBack, backLabel, onError }: {
  roomId: string
  artist: ArtistRef
  /** 지금 우리 우주에 담긴 이 가수 곡 수 (지도 기준, 담기·빼기 뒤 갱신). 모르면 undefined */
  roomCount?: number
  added: Map<string, number>
  pending: Set<string>
  busy: Set<string>
  onAdd: (r: RoomSearchItem) => void
  onBack: () => void
  backLabel: string
  onError: (err: unknown) => boolean
}) {
  const [sort, setSort] = useState<ArtistSort>('popular')
  const [data, setData] = useState<Partial<Record<ArtistSort, Loaded>>>({})
  const [info, setInfo] = useState<SearchArtist | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [moreError, setMoreError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  const [scanTick, setScanTick] = useState(0)
  // 가수가 바뀌면 부모가 key로 새로 만든다 (상태를 처음부터)
  const canLatest = LATEST_READY && artist.artistId != null
  const cur = data[sort]

  // 첫 페이지 (탭을 처음 열 때, 최신순 수집 중이면 몇 초마다 다시)
  useEffect(() => {
    if (data[sort] && !(sort === 'latest' && scanNotice(data[sort]!.scan)?.busy && scanTick > 0)) return
    const ac = new AbortController()
    setError(null)
    roomApi.artistSongs(roomId, artist, sort, 1, ac.signal)
      .then(p => {
        if (p.artist) setInfo(p.artist)
        setData(prev => ({ ...prev, [sort]: { items: p.items, page: p.page ?? 1, hasMore: p.hasMore, scan: p.scan ?? null } }))
      })
      .catch(err => {
        if (ac.signal.aborted || onError(err)) return
        setError(errText(err))
      })
    return () => ac.abort()
    // data는 일부러 빼 둔다: 이미 받은 탭은 다시 받지 않는다(수집 중 다시 읽기는 scanTick, 다시 시도는 retry가 한다)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roomId, artist, sort, scanTick, retry, onError])

  // 최신순 수집 중: 몇 초 뒤 첫 페이지를 다시 읽는다
  const scanBusy = sort === 'latest' && !!scanNotice(cur?.scan)?.busy
  useEffect(() => {
    if (!scanBusy) return
    const t = setTimeout(() => setScanTick(n => n + 1), scanPollMs(scanTick))
    return () => clearTimeout(t)
  }, [scanBusy, cur, scanTick])

  const more = useCallback(async () => {
    if (!cur || !canLoadMore(cur) || loadingMore || scanBusy) return
    setLoadingMore(true)
    setMoreError(null)
    try {
      const p = await roomApi.artistSongs(roomId, artist, sort, cur.page + 1)
      setData(prev => {
        const old = prev[sort]
        if (!old) return prev
        return { ...prev, [sort]: { items: appendSongs(old.items, p.items), page: p.page ?? old.page + 1, hasMore: p.hasMore, scan: p.scan ?? old.scan } }
      })
    } catch (err) {
      if (!onError(err)) setMoreError(errText(err))
    } finally {
      setLoadingMore(false)
    }
  }, [cur, loadingMore, scanBusy, roomId, artist, sort, onError])

  // 끝에 닿으면 이어 불러오기 (버튼도 함께 둔다)
  const sentinel = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = sentinel.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const io = new IntersectionObserver(entries => { if (entries.some(e => e.isIntersecting)) more() }, { rootMargin: '200px' })
    io.observe(el)
    return () => io.disconnect()
  }, [more])

  const merged = mergeArtist({ artistId: artist.artistId, name: artist.name, songCountInRoom: artist.songCountInRoom, onMap: artist.onMap }, info)
  // 담기·빼기 뒤에는 지도 곡 수가 더 최신이다 (I-42 C)
  const shown = roomCount != null ? { ...merged, songCountInRoom: roomCount } : merged
  const sub = artistCardSub(shown)
  const notice = sort === 'latest' ? scanNotice(cur?.scan) : null

  const row = (x: ArtistSongItem, rank: number | null) => {
    const r = asSearchItem(x)
    const key = trackKey(x.item)
    const date = sort === 'latest' ? releaseText(x.releaseDate) : x.item.releaseYear ? String(x.item.releaseYear) : null
    return (
      <SearchRow key={key} roomId={roomId} r={r} rank={rank} sub={date ?? ''} addedLabel="담음" goldRing
        added={r.mine || added.has(key)} pending={pending.has(key)} busy={busy.has(key)} onAdd={() => onAdd(r)} />
    )
  }

  return (
    <section aria-label={`${shown.name} 노래 전체`} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div className={sh.artistHead}>
        <button type="button" className={sh.backButton} aria-label={backLabel} onClick={onBack}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 6l-6 6 6 6" /></svg>
        </button>
        <ArtistFace roomId={roomId} name={shown.name} className={sh.artistHeadOrb} photoUrl={shown.photo?.url} credit={shown.photo} />
        <div className={sh.rowText}>
          <h2 className={sh.artistTitle}>{shown.name}</h2>
          {sub && <span className={sh.songSub}>{sub}</span>}
        </div>
      </div>

      {canLatest && (
        <div className={sh.sortSticky}>
          <nav className={sh.sortTabs} aria-label="노래 정렬">
            {(['popular', 'latest'] as const).map(k => (
              <button key={k} type="button" className={sh.segTab} aria-current={sort === k ? 'page' : undefined} onClick={() => setSort(k)}>
                {k === 'popular' ? '인기순' : '최신순'}
              </button>
            ))}
          </nav>
        </div>
      )}

      {notice && (
        <div className={sh.scanBar} role="status">
          {notice.busy && <span className={sh.dots} aria-hidden="true"><span /><span /><span /></span>}
          <div><strong>{notice.title}</strong><span>{notice.sub}</span></div>
        </div>
      )}

      {error && (
        <div className={sh.empty} style={{ padding: '24px 12px' }} role="alert">
          <p style={{ margin: 0 }}>{error}</p>
          <button type="button" className={sh.sortChip} onClick={() => setRetry(n => n + 1)}>다시 시도</button>
        </div>
      )}
      {!cur && !error && (
        <>
          <span className={s.srOnly} role="status">노래를 불러오는 중이에요</span>
          <ul className={sh.list} aria-hidden="true">
            {[0, 1, 2, 3, 4].map(i => (
              <li key={i} className={sh.row}>
                <span className={`${sh.cover} ${sh.skel}`} />
                <span className={sh.rowText}><span className={sh.skel} style={{ width: '62%', height: 13 }} /><span className={sh.skel} style={{ width: '30%', height: 10 }} /></span>
              </li>
            ))}
          </ul>
        </>
      )}
      {cur && cur.items.length === 0 && !notice?.busy && (
        <p className={sh.center}>{sort === 'latest' ? '발매일이 있는 노래를 아직 찾지 못했어요. 인기순에서 찾아보세요' : '이 가수의 노래를 찾지 못했어요'}</p>
      )}

      {cur && cur.items.length > 0 && (sort === 'popular'
        ? <ul className={sh.list} aria-label="인기순 노래">{cur.items.map((x, i) => row(x, x.rank ?? i + 1))}</ul>
        : groupByRelease(cur.items).map(g => (
          <div key={g.label} style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <h3 className={sh.yearHead}>{g.label}{g.unknown && <small>날짜를 찾지 못한 노래</small>}</h3>
            <ul className={sh.list}>{g.items.map(x => row(x, null))}</ul>
          </div>
        )))}

      {cur && canLoadMore(cur) && !scanBusy && (
        <div ref={moreError ? undefined : sentinel} className={sh.moreRow}>
          {moreError ? <><span role="alert">{moreError}</span><button type="button" className={sh.sortChip} onClick={more}>다시 시도</button></> : loadingMore
            ? <span role="status"><Spinner size={14} /> 이어서 불러오는 중…</span>
            : <button type="button" className={sh.sortChip} onClick={more}>더 보기</button>}
        </div>
      )}
      {cur && !canLoadMore(cur) && cur.items.length > 0 && !scanBusy && (
        <span className={sh.center}>{artistEndText(cur, cur.items.length)}</span>
      )}
    </section>
  )
}
