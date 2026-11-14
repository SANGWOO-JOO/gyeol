import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { roomApi, RoomApiError, type MeView, type MySong, type RoomMap } from '../roomApi'
import { galaxyOfTrack, giftLine, names as namesText } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import type { ToastData } from './Toast'
import { CoverImg } from './CoverImg'
import sh from './shell.module.css'
import { ArtistFace } from './ArtistFace'
import { genreColor } from '../galaxy/faces'
import { FaceChips } from './SearchPanel'
import { coOwners } from './tabLogic'
import { groupSongs, MINE_SORTS, readMineSort, writeMineSort, type GroupHead, type MineSort } from './mineGroups'

/**
 * 4-a 내 노래. ✕로 빼면 친구 화면에서도 사라지고, 알림의 되돌리기로 다시 담는다.
 * 11-14 [최근 담은 순][장르별][아티스트별]: 장르·아티스트는 서버가 곡마다 묶음(group)을 붙여 준다. 머리줄은 위에 붙는다
 */
export function MineTab({ roomId, me, map, version, onChanged, onError, show, onOpenGift, onOpenArtist }: {
  roomId: string
  me: MeView | null
  map: RoomMap | null
  /** 담기·빼기가 일어날 때마다 바뀐다 → 목록을 다시 받는다 */
  version: number
  onChanged: () => void
  onError: (err: unknown) => boolean
  show: (t: Omit<ToastData, 'id'>) => void
  onOpenGift: (boxId: number) => void
  /** 아티스트별 머리줄 → 가수 판 */
  onOpenArtist?: (artistId: number) => void
}) {
  const [sort, setSortRaw] = useState<MineSort>(readMineSort)
  const setSort = (v: MineSort) => { if (v === sort) return; writeMineSort(v); setItems(null); setCursor(null); setSnapshot(null); setSortRaw(v) }
  const [items, setItems] = useState<MySong[] | null>(null)
  const [cursor, setCursor] = useState<number | null>(null)
  const [snapshot, setSnapshot] = useState<number | null>(null)
  const [reload, setReload] = useState(0)
  const [loadingMore, setLoadingMore] = useState(false)
  const [removing, setRemoving] = useState<number | null>(null)
  const galaxy = galaxyOfTrack(map)

  useEffect(() => {
    const ac = new AbortController()
    roomApi.mySongs(roomId, null, 30, ac.signal, sort)
      .then(p => { setItems(p.items); setCursor(p.nextCursor); setSnapshot(p.snapshot ?? null) })
      .catch(err => { if (!ac.signal.aborted && !onError(err)) setItems([]) })
    return () => ac.abort()
  }, [roomId, version, onError, sort, reload])

  async function more() {
    if (cursor == null) return
    setLoadingMore(true)
    try {
      const p = await roomApi.mySongs(roomId, cursor, 30, undefined, sort, snapshot)
      setItems(prev => [...(prev ?? []), ...p.items])
      setCursor(p.nextCursor)
    } catch (err) {
      // 커서 곡을 그사이 뺐으면(409 CURSOR_STALE) 처음부터 다시
      if (err instanceof RoomApiError && err.code === 'CURSOR_STALE') { setItems(null); setCursor(null); setReload(n => n + 1); return }
      if (!onError(err)) show({ title: '더 불러오지 못했어요', tone: 'error' })
    } finally {
      setLoadingMore(false)
    }
  }

  const readd = useCallback(async (song: MySong) => {
    try {
      await roomApi.addTrack(roomId, song.trackId)
      show({ title: '다시 담았어요', sub: song.title })
      onChanged()
    } catch (err) {
      if (!onError(err)) show({ title: '다시 담지 못했어요', sub: err instanceof RoomApiError ? err.message : undefined, tone: 'error' })
    }
  }, [roomId, show, onChanged, onError])

  async function remove(song: MySong) {
    setRemoving(song.id)
    try {
      await roomApi.removeSong(roomId, song.id)
      setItems(prev => prev?.filter(x => x.id !== song.id) ?? null)
      show({ title: '노래를 뺐어요', sub: song.title, cover: { roomId, coverPath: song.coverPath, coverUrl: song.coverUrl }, action: { label: '되돌리기', run: () => readd(song) } })
      onChanged()
    } catch (err) {
      if (!onError(err)) show({ title: '빼지 못했어요', tone: 'error' })
    } finally {
      setRemoving(null)
    }
  }

  // 곡 줄: 장르별은 가수만, 아티스트별은 "발매 연도 · 앨범"(값 있는 것만), 최근 순은 가수 · 장르
  const meId = me?.member.id ?? -1
  const members = map?.members ?? []
  const row = (song: MySong): ReactNode => {
    const sub = sort === 'genre' ? song.artist : sort === 'artist' ? ([song.releaseYear, song.album].filter(Boolean).join(' · ') || null) : [song.artist, galaxy.get(song.trackId)].filter(Boolean).join(' · ')
    const others = song.sharedWith > 0 ? coOwners(map, song.trackId, meId) : []
    const names = others.map(id => members.find(m => m.id === id)?.nickname).filter((n): n is string => !!n)
    return (
      <li key={song.id} className={`${sh.row} ${song.sharedWith > 0 ? sh.rowShared : ''}`} style={{ paddingRight: 4 }}>
        <CoverImg roomId={roomId} cover={song} className={sh.cover} />
        <span className={sh.rowText}>
          <span className={sh.songTitle}>{song.title}</span>
          {sub && <span className={sh.songSub}>{sub}</span>}
          {song.sharedWith > 0 && (names.length > 0
            ? <FaceChips names={names} members={members} text={`${namesText(names)}과 같이 담음`} />
            : <span className={sh.faceChips}>친구 {song.sharedWith}명과 같이 담음</span>)}
        </span>
        <button type="button" className={sh.remove} aria-label={`${song.title} 빼기`} disabled={removing === song.id} onClick={() => remove(song)}>
          {removing === song.id ? <Spinner size={14} /> : (
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          )}
        </button>
      </li>
    )
  }


  const total = me?.songCount ?? items?.length ?? 0
  const gift = me?.gift

  return (
    <section aria-label={`내 노래 ${total}곡`} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {gift && <GiftLine gift={gift} songCount={total} onOpen={onOpenGift} />}

      <div className={sh.mineHead}>
        <h2>내 노래 <strong>{total}</strong>곡</h2>
        {me && <span className={sh.todayPill} title="하루에 담을 수 있는 곡"><i style={{ width: `${Math.min(100, (me.todayAdded / Math.max(1, me.dailyLimit)) * 100)}%` }} aria-hidden="true" /><span>오늘 {me.todayAdded}/{me.dailyLimit}곡</span></span>}
      </div>

      <div className={sh.chipsRow} role="group" aria-label="정렬">
        {MINE_SORTS.map(k => (
          <button key={k} type="button" className={sh.sortChip} aria-pressed={sort === k} onClick={() => setSort(k)}>{SORT_LABEL[k]}</button>
        ))}
      </div>

      {items === null && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
      {items !== null && items.length === 0 && (
        <div className={sh.empty} style={{ padding: '24px 12px' }}>
          <p>아직 담은 노래가 없어요. 검색 탭에서 첫 노래를 담아 보세요.</p>
        </div>
      )}

      {items && items.length > 0 && (sort === 'recent'
        ? <ul className={sh.songList}>{items.map(row)}</ul>
        : groupSongs(sort, items).map(g => (
          <section key={g.head.key} className={sh.mineGroup} aria-label={`${g.head.title} ${g.head.count ?? g.items.length}곡`}>
            <GroupHeadRow sort={sort} head={g.head} shown={g.items.length} roomId={roomId} onOpenArtist={onOpenArtist} />
            <ul className={sh.songList}>{g.items.map(row)}</ul>
          </section>
        )))}

      {cursor != null && (
        <button type="button" className={sh.inviteBox} style={{ borderStyle: 'solid', height: 44, fontSize: 14 }} onClick={more} disabled={loadingMore}>
          {loadingMore ? <Spinner size={16} /> : '더 보기'}
        </button>
      )}
      {items && items.length > 0 && <span className={sh.center}>노래를 빼면 친구들 화면에서도 바로 사라져요</span>}
    </section>
  )
}

/** 4-l 선물 줄: 내 노래 탭 맨 위에 늘 있다. 열 수 있는 상자가 있으면 눌러서 4-j */
function GiftLine({ gift, songCount, onOpen }: { gift: NonNullable<MeView['gift']>; songCount: number; onOpen: (boxId: number) => void }) {
  const g = giftLine(gift)
  const inner = (
    <>
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="4" y="10" width="16" height="10" rx="2" /><path d="M3 7h18v3H3zM12 7v13M12 7c-2-4-6-3-5-1 .6 1.2 3 1 5 1zM12 7c2-4 6-3 5-1-.6 1.2-3 1-5 1z" /></svg>
      <span>{g.text}</span>
      {/* 남은 새 그림 장수까지 보일 때는 한 줄에 들어가게 곡 수를 뺀다(아래 제목에 곡 수가 있음) */}
      {!(g.canOpen && gift.imagesLeftToday != null) && <span>내 노래 {songCount}곡</span>}
    </>
  )
  const cls = `${sh.giftLine} ${g.gold ? sh.giftLineGold : ''}`
  return g.canOpen && gift.readyBoxId != null
    ? <button type="button" className={`${cls} ${sh.giftLineButton}`} onClick={() => onOpen(gift.readyBoxId!)}>{inner}</button>
    : <div className={cls}>{inner}</div>
}

const SORT_LABEL: Record<MineSort, string> = { recent: '최근 담은 순', genre: '장르별', artist: '아티스트별' }

/** 묶음 머리줄: 장르는 지도 구역 색 점, 아티스트는 작은 얼굴(누르면 가수 판). 스크롤하면 위에 붙는다 */
function GroupHeadRow({ sort, head, shown, roomId, onOpenArtist }: { sort: MineSort; head: GroupHead; shown: number; roomId: string; onOpenArtist?: (id: number) => void }) {
  const count = `${head.count ?? shown}곡`
  if (sort === 'artist') {
    const faceUrl = head.face && (head.face.kind === 'PHOTO' || head.face.kind === 'COVER') ? head.face.url : null
    const body = (
      <>
        <ArtistFace roomId={roomId} name={head.title} className={sh.groupFace} photoUrl={faceUrl} />
        <strong>{head.title}</strong><span>{count}</span>
      </>
    )
    return head.artistId != null && onOpenArtist
      ? <button type="button" className={sh.groupHead} onClick={() => onOpenArtist(head.artistId!)} aria-label={`${head.title} 가수 판 열기, ${count}`}>{body}
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg></button>
      : <div className={sh.groupHead}>{body}</div>
  }
  return (
    <div className={sh.groupHead}>
      <i className={sh.groupDot} style={{ background: head.galaxyId != null ? genreColor(head.title, head.galaxyId) : '#5e5794' }} aria-hidden="true" />
      <strong>{head.title}</strong><span>{count}</span>
    </div>
  )
}
