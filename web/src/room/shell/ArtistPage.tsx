import { useCallback, useEffect, useRef, useState } from 'react'
import { roomApi, RoomApiError, type ArtistSongItem, type ArtistSort, type RoomSearchItem, type SearchArtist } from '../roomApi'
import { appendSongs, artistCardSub, asSearchItem, canLoadMore, groupByRelease, releaseText, scanNotice, trackKey } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import { SearchRow } from './SearchPanel'
import sh from './shell.module.css'

/** 가수 화면을 연 곳: 검색의 가수 카드, 또는 지도 행성의 가수 판(back = 그 행성) */
export interface ArtistRef { artistId: number | null; name: string; fromPlanet?: number }

interface Loaded { items: ArtistSongItem[]; page: number; hasMore: boolean; scan: ArtistSongsScan }
type ArtistSongsScan = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | null
const SCAN_POLL_MS = 4000
/** ③ 최신순(V27·worker)은 백엔드 마지막 단계: 준비되면 켠다 */
const LATEST_READY = false

/**
 * I-30 가수 화면 (캔버스 4-m·4-m2): [인기순][최신순] 20곡씩 이어 불러오기.
 * 담기·금빛(친구가 담은 곡 → 나도 담기)·담음은 방 검색과 같다. 담는 일은 부모(RoomShell)가 한다.
 * 최신순은 DB에 있는 가수(artistId)만: 발매일을 처음 모으는 동안(scan RUNNING)은 모은 것만 보이고 몇 초마다 다시 읽는다.
 */
export function ArtistPage({ roomId, artist, added, pending, busy, onAdd, onBack, backLabel, onError }: {
  roomId: string
  artist: ArtistRef
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
        setError(err instanceof RoomApiError ? err.message : '노래를 불러오지 못했어요')
      })
    return () => ac.abort()
    // data는 일부러 빼 둔다: 이미 받은 탭은 다시 받지 않는다(수집 중 다시 읽기는 scanTick이 한다)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roomId, artist, sort, scanTick, onError])

  // 최신순 수집 중: 몇 초 뒤 첫 페이지를 다시 읽는다
  const scanBusy = sort === 'latest' && !!scanNotice(cur?.scan)?.busy
  useEffect(() => {
    if (!scanBusy) return
    const t = setTimeout(() => setScanTick(n => n + 1), SCAN_POLL_MS)
    return () => clearTimeout(t)
  }, [scanBusy, cur])

  const more = useCallback(async () => {
    if (!cur || !canLoadMore(cur) || loadingMore || scanBusy) return
    setLoadingMore(true)
    try {
      const p = await roomApi.artistSongs(roomId, artist, sort, cur.page + 1)
      setData(prev => {
        const old = prev[sort]
        if (!old) return prev
        return { ...prev, [sort]: { items: appendSongs(old.items, p.items), page: p.page ?? old.page + 1, hasMore: p.hasMore, scan: p.scan ?? old.scan } }
      })
    } catch (err) {
      if (!onError(err)) setError(err instanceof RoomApiError ? err.message : '더 불러오지 못했어요')
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

  const shown: SearchArtist = info ?? { artistId: artist.artistId, name: artist.name }
  const sub = artistCardSub(shown)
  const notice = sort === 'latest' ? scanNotice(cur?.scan) : null

  const row = (x: ArtistSongItem, rank: number | null) => {
    const r = asSearchItem(x)
    const key = trackKey(x.item)
    const date = sort === 'latest' ? releaseText(x.releaseDate) : x.item.releaseYear ? String(x.item.releaseYear) : null
    return (
      <SearchRow key={key} r={r} rank={rank} sub={date ?? ''} addedLabel="담음" goldRing
        added={r.mine || added.has(key)} pending={pending.has(key)} busy={busy.has(key)} onAdd={() => onAdd(r)} />
    )
  }

  return (
    <section aria-label={`${shown.name} 노래 전체`} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div className={sh.artistHead}>
        <button type="button" className={sh.backButton} aria-label={backLabel} onClick={onBack}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 6l-6 6 6 6" /></svg>
        </button>
        <span className={sh.artistHeadOrb} aria-hidden="true">{Array.from(shown.name)[0] ?? ''}</span>
        <div className={sh.rowText}>
          <h2 className={sh.artistTitle}>{shown.name}</h2>
          {sub && <span className={sh.songSub}>{sub}</span>}
        </div>
      </div>

      {canLatest && (
        <nav className={sh.sortTabs} aria-label="노래 정렬">
          {(['popular', 'latest'] as const).map(k => (
            <button key={k} type="button" className={sh.segTab} aria-current={sort === k ? 'page' : undefined} onClick={() => setSort(k)}>
              {k === 'popular' ? '인기순' : '최신순'}
            </button>
          ))}
        </nav>
      )}

      {notice && (
        <div className={sh.scanBar} role="status">
          {notice.busy && <span className={sh.dots} aria-hidden="true"><span /><span /><span /></span>}
          <div><strong>{notice.title}</strong><span>{notice.sub}</span></div>
        </div>
      )}

      {error && <p className={sh.center} role="alert" style={{ color: 'var(--r-error)' }}>{error}</p>}
      {!cur && !error && <p className={sh.center} role="status"><Spinner size={14} /> 노래를 불러오는 중…</p>}
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
        <div ref={sentinel} className={sh.moreRow}>
          {loadingMore
            ? <span role="status"><Spinner size={14} /> 이어서 불러오는 중…</span>
            : <button type="button" className={sh.sortChip} onClick={more}>더 보기</button>}
        </div>
      )}
      {cur && !canLoadMore(cur) && cur.items.length > 0 && !scanBusy && (
        <span className={sh.center}>{cur.hasMore ? '여기까지 보여 드려요 · 다른 노래는 검색으로 찾아보세요' : '이 가수의 노래를 다 봤어요'}</span>
      )}
    </section>
  )
}
