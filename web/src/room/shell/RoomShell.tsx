import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import { keyStore } from '../keyStore'
import { roomApi, RoomApiError, type MeView, type RoomMap, type RoomSearchItem, type SongView } from '../roomApi'
import { addedMessage, addErrorText, failText, mapCounts } from '../roomLogic'
import { useRoomTheme } from '../components/RoomPage'
import { Avatar } from '../components/Avatar'
import { Wordmark } from '../components/Wordmark'
import { GalaxyView } from '../galaxy/GalaxyView'
import { itemKey, SearchPanel } from './SearchPanel'
import { Toast, type ToastData } from './Toast'
import s from '../room.module.css'
import sh from './shell.module.css'

// 방 화면 틀 (캔버스 4): 머리(로고·얼굴·초대·방 이름) · 아래 탭 4개(지도·놀이터·검색·내 노래) · 탭 바 위 알림.
// 지도는 5초마다 ETag로 바뀌었을 때만 받고, 식별을 맡긴 노래(202)는 2초마다 상태를 본다.

type Tab = 'map' | 'play' | 'search' | 'mine'
const TABS: { id: Tab; label: string; icon: ReactNode }[] = [
  { id: 'map', label: '지도', icon: <><circle cx="12" cy="12" r="3.2" /><ellipse cx="12" cy="12" rx="9" ry="4.2" transform="rotate(-20 12 12)" /></> },
  { id: 'play', label: '놀이터', icon: <path d="M12 3l2.2 5.6L20 9.3l-4.4 3.8L17 19l-5-3-5 3 1.4-5.9L4 9.3l5.8-.7z" /> },
  { id: 'search', label: '검색', icon: <><circle cx="11" cy="11" r="6.5" /><path d="M20 20l-4.2-4.2" /></> },
  { id: 'mine', label: '내 노래', icon: <><path d="M9 18V6l10-2v12" /><circle cx="6.5" cy="18" r="2.5" /><circle cx="16.5" cy="16" r="2.5" /></> },
]
const isTab = (v: string | null): v is Tab => TABS.some(t => t.id === v)

interface Pending { requestId: number; key: string; title: string; artist: string; sameSong: string[] }
interface NotFound { title: string; text: string }

export function RoomShell({ roomId, onLostKey }: { roomId: string; onLostKey: () => void }) {
  useRoomTheme()
  const [params, setParams] = useSearchParams()
  const tab: Tab = isTab(params.get('tab')) ? (params.get('tab') as Tab) : 'map'
  const setTab = (t: Tab) => setParams(t === 'map' ? {} : { tab: t }, { replace: true })

  const [me, setMe] = useState<MeView | null>(null)
  const [map, setMap] = useState<RoomMap | null>(null)
  const [toast, setToast] = useState<ToastData | null>(null)
  const [query, setQuery] = useState('')
  const [added, setAdded] = useState<Map<string, number>>(new Map())
  const [busy, setBusy] = useState<Set<string>>(new Set())
  const [pending, setPending] = useState<Pending[]>([])
  const [notFound, setNotFound] = useState<NotFound | null>(null)
  const [pollTick, setPollTick] = useState(0)
  const etag = useRef<string | null>(null)
  const toastId = useRef(0)

  const show = useCallback((t: Omit<ToastData, 'id'>) => setToast({ ...t, id: ++toastId.current }), [])
  const closeToast = useCallback(() => setToast(null), [])

  /** 열쇠가 무효(401)면 이 기기에서 지우고 초대·다시 들어오기 화면으로 */
  const handle = useCallback((err: unknown): boolean => {
    if (err instanceof RoomApiError && err.status === 401) { keyStore.remove(roomId); onLostKey(); return true }
    return false
  }, [roomId, onLostKey])

  const refreshMap = useCallback(async (force = false) => {
    try {
      const r = await roomApi.map(roomId, force ? null : etag.current)
      if (r) { etag.current = r.etag; setMap(r.map) }
    } catch (err) { handle(err) }
  }, [roomId, handle])

  const refreshMe = useCallback(async () => {
    try {
      const m = await roomApi.me(roomId)
      setMe(m)
      keyStore.update(roomId, { nickname: m.member.nickname, roomName: m.roomName })
    } catch (err) { handle(err) }
  }, [roomId, handle])

  useEffect(() => { refreshMe(); refreshMap(true) }, [refreshMe, refreshMap])

  // 지도 5초 새로고침 (탭이 보일 때만)
  useEffect(() => {
    const t = setInterval(() => { if (document.visibilityState === 'visible') refreshMap() }, 5000)
    return () => clearInterval(t)
  }, [refreshMap])

  const announceAdded = useCallback((song: SongView, sameSong: string[]) => {
    const msg = addedMessage(song, sameSong)
    setAdded(prev => new Map(prev).set(`t${song.trackId}`, song.id))
    show({
      title: msg.title, tone: msg.gold ? 'gold' : 'normal', cover: song.coverUrl,
      sub: `${song.title} · ${song.artist}`,
      action: { label: '되돌리기', run: () => undo(song) },
    })
    refreshMap(true); refreshMe()
  }, [show, refreshMap, refreshMe])

  async function undo(song: SongView) {
    try {
      await roomApi.removeSong(roomId, song.id)
      setAdded(prev => { const n = new Map(prev); n.delete(`t${song.trackId}`); return n })
      show({ title: '담은 노래를 뺐어요', sub: song.title })
      refreshMap(true); refreshMe()
    } catch (err) {
      if (!handle(err)) show({ title: '빼지 못했어요', sub: err instanceof RoomApiError ? err.message : undefined, tone: 'error' })
    }
  }

  async function add(r: RoomSearchItem) {
    const key = itemKey(r.item)
    setBusy(prev => new Set(prev).add(key))
    try {
      const res = await roomApi.addSong(roomId, r.item)
      if (res.status === 'ADDED' && res.song) {
        setAdded(prev => new Map(prev).set(key, res.song!.id))
        announceAdded(res.song, r.sameSong)
      } else if (res.requestId != null) {
        setPending(prev => [...prev, { requestId: res.requestId!, key, title: r.item.title, artist: r.item.artist, sameSong: r.sameSong }])
        show({ title: '노래를 찾고 있어요', sub: `${r.item.title} · 찾으면 바로 우주에 떠요` })
      }
    } catch (err) {
      if (handle(err)) return
      if (err instanceof RoomApiError && err.code === 'ALREADY_ADDED') setAdded(prev => new Map(prev).set(key, -1))
      show({ title: err instanceof RoomApiError ? addErrorText(err.code, err.message) : '담지 못했어요', tone: 'error' })
    } finally {
      setBusy(prev => { const n = new Set(prev); n.delete(key); return n })
    }
  }

  // 식별을 맡긴 노래 상태 확인 (4-g 찾는 중 → 4-e 떴어요 / 4-i 못 찾음)
  useEffect(() => {
    if (pending.length === 0) return
    const ac = new AbortController()
    const t = setTimeout(async () => {
      for (const p of pending) {
        try {
          const r = await roomApi.songRequest(roomId, p.requestId, ac.signal)
          if (r.status === 'IDENTIFYING') continue
          setPending(prev => prev.filter(x => x.requestId !== p.requestId))
          if (r.status === 'DONE' && r.song) {
            setAdded(prev => new Map(prev).set(p.key, r.song!.id))
            if (r.failReason === 'ALREADY_ADDED') show({ title: '이미 담은 노래예요', sub: r.song.title })
            else announceAdded(r.song, p.sameSong)
          } else {
            setToast(null) // "찾고 있어요" 알림이 남아 있으면 못 찾음 안내와 어긋난다
            setNotFound({ title: p.title, text: failText(r.failReason) })
          }
        } catch (err) {
          if (ac.signal.aborted) return
          if (handle(err)) return
        }
      }
      // 아직 찾는 중인 노래가 남아 있으면 2초 뒤 다시 본다
      if (!ac.signal.aborted) setPollTick(n => n + 1)
    }, 2000)
    return () => { clearTimeout(t); ac.abort() }
  }, [pending, pollTick, roomId, show, announceAdded, handle])

  async function invite() {
    const url = `${location.origin}/rooms/${roomId}`
    const name = me?.roomName ?? '우리 우주'
    try {
      if (navigator.share) { await navigator.share({ title: name, text: `${name}에 노래를 담아 줘`, url }); return }
      await navigator.clipboard.writeText(url)
      show({ title: '초대 링크를 복사했어요', sub: '카톡에 붙여 넣어 보내 주세요' })
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      show({ title: '링크를 복사하지 못했어요', sub: url, tone: 'error' })
    }
  }

  const members = map?.members ?? []
  const counts = mapCounts(map)

  return (
    <div className={sh.shell}>
      <header className={sh.header}>
        <div className={sh.headRow}>
          <Link to="/" aria-label="첫 화면"><Wordmark size={20} /></Link>
          <div className={sh.headRight}>
            <button type="button" className={sh.faces} aria-label={`참가자 ${members.length}명 보기`} onClick={() => setTab('mine')}>
              {members.map(m => <Avatar key={m.id} nickname={m.nickname} colorIdx={m.colorIdx} size={32} />)}
            </button>
            <button type="button" className={sh.iconButton} aria-label="친구 초대하기" onClick={invite}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 15V3" /><path d="M7 8l5-5 5 5" /><path d="M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5" /></svg>
            </button>
          </div>
        </div>
        <div className={sh.titleRow}>
          <h1 className={sh.roomName}>{me?.roomName ?? keyStore.get(roomId)?.roomName ?? ''}</h1>
          <span className={sh.counts}>노래 {counts.songs}곡 · 가수 {counts.artists}명</span>
        </div>
      </header>

      <main className={sh.body}>
        {pending.length > 0 && (
          <div className={sh.pending} role="status">
            <span className={s.spin} aria-hidden="true" style={{ display: 'inline-flex' }}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><path d="M12 3a9 9 0 1 0 9 9" /></svg>
            </span>
            <span>노래를 찾는 중이에요 · <strong>{pending[0].title}</strong>{pending.length > 1 ? ` 외 ${pending.length - 1}곡` : ''}</span>
          </div>
        )}

        {notFound && (
          <section className={sh.dialog} role="alertdialog" aria-labelledby="nf-title">
            <h2 id="nf-title">노래를 찾지 못해 담지 않았어요</h2>
            <p><strong>{notFound.title}</strong> · {notFound.text}</p>
            <div className={sh.dialogButtons}>
              <button type="button" className={s.primary} style={{ height: 44, fontSize: 14 }} onClick={() => { setQuery(notFound.title); setNotFound(null); setTab('search') }}>다른 이름으로 찾기</button>
              <button type="button" className={s.secondary} style={{ height: 44, fontSize: 14 }} onClick={() => setNotFound(null)}>닫기</button>
            </div>
          </section>
        )}

        {tab === 'map' && (map
          ? <GalaxyView map={map} meId={me?.member.id ?? -1} onAddFirst={() => setTab('search')} />
          : <p className={sh.center} role="status">우주를 불러오는 중…</p>)}

        {tab === 'search' && (
          <SearchPanel roomId={roomId} query={query} setQuery={setQuery} added={added}
            pending={new Set(pending.map(p => p.key))} busy={busy} onAdd={add} />
        )}

        {tab === 'play' && <Soon title="놀이터가 곧 열려요" text="친구와 코드가 얼마나 맞는지 알아보는 '코드 맞추기'를 준비하고 있어요." />}
        {tab === 'mine' && <Soon title="내 노래·참가자 판을 준비하고 있어요" text={me ? `지금 ${me.songCount}곡을 담았어요 · 오늘 ${me.todayAdded}/${me.dailyLimit}곡` : ''} />}
      </main>

      <Toast toast={toast} onClose={closeToast} />

      <nav className={sh.tabs} aria-label="방 메뉴">
        {TABS.map(t => (
          <button key={t.id} type="button" className={sh.tab} aria-current={tab === t.id ? 'page' : undefined} onClick={() => setTab(t.id)}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{t.icon}</svg>
            {t.label}
          </button>
        ))}
      </nav>
    </div>
  )
}

/** 4단계(내 노래·참가자)·7단계(놀이터)에서 채울 탭 */
function Soon({ title, text }: { title: string; text: string }) {
  return (
    <section className={sh.empty}>
      <h2>{title}</h2>
      {text && <p>{text}</p>}
    </section>
  )
}
