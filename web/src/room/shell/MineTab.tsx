import { useCallback, useEffect, useState } from 'react'
import { roomApi, RoomApiError, type MeView, type MySong, type RoomMap } from '../roomApi'
import { galaxyOfTrack, giftLine } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import type { ToastData } from './Toast'
import sh from './shell.module.css'

/**
 * 4-a 내 노래 (최근 담은 순). ✕로 빼면 친구 화면에서도 사라지고, 알림의 되돌리기로 다시 담는다.
 * 장르별·아티스트별 정렬은 2차 (API가 최근 순만 지원, room-api-db.md 7절).
 */
export function MineTab({ roomId, me, map, version, onChanged, onError, show, onOpenGift }: {
  roomId: string
  me: MeView | null
  map: RoomMap | null
  /** 담기·빼기가 일어날 때마다 바뀐다 → 목록을 다시 받는다 */
  version: number
  onChanged: () => void
  onError: (err: unknown) => boolean
  show: (t: Omit<ToastData, 'id'>) => void
  onOpenGift: (boxId: number) => void
}) {
  const [items, setItems] = useState<MySong[] | null>(null)
  const [cursor, setCursor] = useState<number | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [removing, setRemoving] = useState<number | null>(null)
  const galaxy = galaxyOfTrack(map)

  useEffect(() => {
    const ac = new AbortController()
    roomApi.mySongs(roomId, null, 30, ac.signal)
      .then(p => { setItems(p.items); setCursor(p.nextCursor) })
      .catch(err => { if (!ac.signal.aborted && !onError(err)) setItems([]) })
    return () => ac.abort()
  }, [roomId, version, onError])

  async function more() {
    if (cursor == null) return
    setLoadingMore(true)
    try {
      const p = await roomApi.mySongs(roomId, cursor, 30)
      setItems(prev => [...(prev ?? []), ...p.items])
      setCursor(p.nextCursor)
    } catch (err) {
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
      show({ title: '노래를 뺐어요', sub: song.title, cover: song.coverUrl, action: { label: '되돌리기', run: () => readd(song) } })
      onChanged()
    } catch (err) {
      if (!onError(err)) show({ title: '빼지 못했어요', tone: 'error' })
    } finally {
      setRemoving(null)
    }
  }

  const total = me?.songCount ?? items?.length ?? 0
  const gift = me?.gift

  return (
    <section aria-label={`내 노래 ${total}곡`} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {gift && <GiftLine gift={gift} songCount={total} onOpen={onOpenGift} />}

      <div className={sh.mineHead}>
        <h2>내 노래 <strong>{total}</strong>곡</h2>
        {me && <span className={sh.counts}>오늘 {me.todayAdded}/{me.dailyLimit}곡</span>}
      </div>

      <div className={sh.chipsRow} role="group" aria-label="정렬">
        <button type="button" className={sh.sortChip} aria-pressed="true">최근 담은 순</button>
        <button type="button" className={sh.sortChip} aria-pressed="false" disabled title="곧 열려요">장르별</button>
        <button type="button" className={sh.sortChip} aria-pressed="false" disabled title="곧 열려요">아티스트별</button>
      </div>

      {items === null && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
      {items !== null && items.length === 0 && (
        <div className={sh.empty} style={{ padding: '24px 12px' }}>
          <p>아직 담은 노래가 없어요. 검색 탭에서 첫 노래를 담아 보세요.</p>
        </div>
      )}

      {items && items.length > 0 && (
        <ul className={sh.list}>
          {items.map(song => (
            <li key={song.id} className={sh.row} style={{ paddingRight: 4 }}>
              {song.coverUrl ? <img src={song.coverUrl} alt="" className={sh.cover} loading="lazy" /> : <span className={sh.cover} aria-hidden="true" />}
              <span className={sh.rowText}>
                <span className={sh.songTitle}>{song.title}</span>
                <span className={sh.songSub}>{[song.artist, galaxy.get(song.trackId)].filter(Boolean).join(' · ')}</span>
                {song.sharedWith > 0 && <span className={`${sh.badge} ${sh.badgeGold}`}>친구 {song.sharedWith}명과 같이 담음</span>}
              </span>
              <button type="button" className={sh.remove} aria-label={`${song.title} 빼기`} disabled={removing === song.id} onClick={() => remove(song)}>
                {removing === song.id ? <Spinner size={14} /> : (
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}

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
