import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { keyStore } from '../keyStore'
import { roomApi, RoomApiError, type MeView, type MemberRow, type RoomMap, type RoomSummary, type RoomSearchItem, type SongView, type GiftNotice } from '../roomApi'
import { giftNoticeToast } from '../giftNotice'
import { inviteMessage } from '../inviteMessage'
import { addedMessage, addErrorText, failText, giftLine, isNewArtist, mapCounts } from '../roomLogic'
import { useRoomTheme } from '../components/RoomPage'
import { Avatar, AvatarRoom } from '../components/Avatar'
import { Wordmark } from '../components/Wordmark'
import { GalaxyView } from '../galaxy/GalaxyView'
import { useFreshMarks } from '../galaxy/fresh'
import { keepFocus, toggleFocus } from '../galaxy/focus'
import { itemKey, SearchPanel } from './SearchPanel'
import { MineTab } from './MineTab'
import { PeopleSheet } from './PeopleSheet'
import { ArtistSheet } from './ArtistSheet'
import { ArtistPage, type ArtistRef } from './ArtistPage'
import { MemberSheet, MeSheet, SettingsSheet } from './AccountSheets'
import { GiftSheet } from './GiftSheet'
import { PlayTab } from './PlayTab'
import { Toast, type ToastData } from './Toast'
import { SheetModeContext } from './Sheet'
import { useWide } from './useWide'
import { HeadMenu } from './HeadMenu'
import lst from '../galaxy/list.module.css'
import { PeopleIcon, PlayIcon, RecordIcon, RoomPlanet, ScopeIcon } from './RoomIcons'
import { memberColor } from '../roomApi'
import { expandsPanel, readCollapsed, writeCollapsed } from './panelPref'
import s from '../room.module.css'
import sh from './shell.module.css'

// 방 화면 틀 (캔버스 4): 머리(로고·얼굴·초대·방 이름) · 아래 탭 4개(지도·놀이터·검색·내 노래) · 탭 바 위 알림.
// PC(1024px~, 캔버스 4-PC·⑰): 지도는 늘 왼쪽, 오른쪽 판 탭은 놀이터·검색·내 노래·참가자.
// 가수 자세히·내 정보는 오른쪽 판에, 설정·관리·선물은 방 위 대화상자로, 알림은 지도 아래 가운데.
// 지도는 5초마다 ETag로 바뀌었을 때만 받고, 식별을 맡긴 노래(202)는 2초마다 상태를 본다.

type Tab = 'map' | 'play' | 'search' | 'mine'
const TABS: { id: Tab; label: string; icon: ReactNode }[] = [
  { id: 'map', label: '지도', icon: <><circle cx="12" cy="12" r="3.2" /><ellipse cx="12" cy="12" rx="9" ry="4.2" transform="rotate(-20 12 12)" /></> },
  { id: 'play', label: '놀이터', icon: <path d="M12 3l2.2 5.6L20 9.3l-4.4 3.8L17 19l-5-3-5 3 1.4-5.9L4 9.3l5.8-.7z" /> },
  { id: 'search', label: '검색', icon: <><circle cx="11" cy="11" r="6.5" /><path d="M20 20l-4.2-4.2" /></> },
  { id: 'mine', label: '내 노래', icon: <><path d="M9 18V6l10-2v12" /><circle cx="6.5" cy="18" r="2.5" /><circle cx="16.5" cy="16" r="2.5" /></> },
]
const isTab = (v: string | null): v is Tab => TABS.some(t => t.id === v)
type PanelTab = 'play' | 'search' | 'mine' | 'people'
const PANEL_TABS: PanelTab[] = ['play', 'search', 'mine', 'people']
const isPanelTab = (v: string | null): v is PanelTab => PANEL_TABS.includes(v as PanelTab)

type SheetState =
  | { kind: 'people' } | { kind: 'artist'; id: number } | { kind: 'me' } | { kind: 'member'; member: MemberRow } | { kind: 'settings' } | { kind: 'gift'; boxId: number } | null

interface Pending { requestId: number; key: string; title: string; artist: string; sameSong: string[] }
interface NotFound { title: string; text: string }

export function RoomShell({ roomId, onLostKey }: { roomId: string; onLostKey: () => void }) {
  useRoomTheme()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const tab: Tab = isTab(params.get('tab')) ? (params.get('tab') as Tab) : 'map'
  const setTab = (t: Tab) => setParams(t === 'map' ? {} : { tab: t }, { replace: true })
  const wide = useWide()
  // PC 오른쪽 판 탭: 주소의 tab 값을 같이 쓴다 (지도 탭이면 내 노래)
  const rawTab = params.get('tab')
  const panelTab: PanelTab = isPanelTab(rawTab) ? rawTab : 'mine'

  const [me, setMe] = useState<MeView | null>(null)
  const [map, setMap] = useState<RoomMap | null>(null)
  // 지도 연출(9-3): 지도 탭을 마지막으로 본 뒤 새로 생긴 위성·행성·겹침
  const fresh = useFreshMarks(map, wide || tab === 'map')
  const [toast, setToast] = useState<ToastData | null>(null)
  const [query, setQuery] = useState('')
  /** I-30 가수 화면: 검색 탭(PC는 오른쪽 판 검색) 자리에 뜬다 */
  const [artistPage, setArtistPage] = useState<ArtistRef | null>(null)
  const [added, setAdded] = useState<Map<string, number>>(new Map())
  const [busy, setBusy] = useState<Set<string>>(new Set())
  const [pending, setPending] = useState<Pending[]>([])
  const [notFound, setNotFound] = useState<NotFound | null>(null)
  const [pollTick, setPollTick] = useState(0)
  const [sheet, setSheetRaw] = useState<SheetState>(null)
  // PC 오른쪽 판 접기 (11-14): 이 브라우저에 기억, 탭을 고르거나 가수·내 정보 판이 열리면 저절로 펼침
  const [collapsed, setCollapsedRaw] = useState(readCollapsed)
  const setCollapsed = useCallback((v: boolean) => { setCollapsedRaw(v); writeCollapsed(v) }, [])
  /** PC에선 참가자 판이 따로 없고 오른쪽 판의 참가자 탭이다 */
  const setSheet = useCallback((next: SheetState | ((prev: SheetState) => SheetState)) => {
    if (typeof next !== 'function' && wide && next?.kind === 'people') {
      setParams({ tab: 'people' }, { replace: true })
      setSheetRaw(null)
      return
    }
    if (typeof next !== 'function' && expandsPanel({ kind: 'sheet', sheet: next?.kind })) setCollapsed(false)
    setSheetRaw(next)
  }, [wide, setParams, setCollapsed])
  const setPanel = (t: PanelTab) => {
    if (expandsPanel({ kind: 'tab' })) setCollapsed(false)
    setSheetRaw(prev => (prev?.kind === 'artist' || prev?.kind === 'me' ? null : prev))
    setParams({ tab: t }, { replace: true })
  }
  // I-26 PC: 지도에서 이 사람 노래만 밝게 (참가자 탭·색 안내가 같이 쓴다). 휴대폰 배치에선 쓰지 않는다
  const [focusId, setFocusId] = useState<number | null>(null)
  const onFocus = useCallback((id: number) => setFocusId(cur => toggleFocus(cur, id)), [])
  const clearFocus = useCallback(() => setFocusId(null), [])
  // 담기·빼기가 일어날 때마다 올린다 → 내 노래·참가자·가수 판이 다시 받는다
  const [version, setVersion] = useState(0)
  const [summary, setSummary] = useState<RoomSummary | null>(null)
  const etag = useRef<string | null>(null)
  const toastId = useRef(0)

  const show = useCallback((t: Omit<ToastData, 'id'>) => setToast({ ...t, id: ++toastId.current }), [])
  const closeToast = useCallback(() => setToast(null), [])

  /** 열쇠가 무효(401)면 이 기기에서 지우고 초대·다시 들어오기 화면으로 */
  const handle = useCallback((err: unknown): boolean => {
    if (err instanceof RoomApiError && err.status === 401) { keyStore.remove(roomId); onLostKey(); return true }
    return false
  }, [roomId, onLostKey])

  /** 새로 받은 지도 (바뀌지 않았거나 실패하면 null) */
  const refreshMap = useCallback(async (force = false): Promise<RoomMap | null> => {
    try {
      const r = await roomApi.map(roomId, force ? null : etag.current)
      if (r) { etag.current = r.etag; setMap(r.map); return r.map }
    } catch (err) { handle(err) }
    return null
  }, [roomId, handle])

  const refreshMe = useCallback(async () => {
    try {
      const m = await roomApi.me(roomId)
      setMe(m)
      keyStore.update(roomId, { nickname: m.member.nickname, roomName: m.roomName })
      keyStore.touch(roomId) // I-70: 마지막 사용부터 180일
    } catch (err) { handle(err) }
  }, [roomId, handle])

  useEffect(() => { refreshMe(); refreshMap(true) }, [refreshMe, refreshMap])
  const refreshSummary = useCallback(() => { roomApi.summary(roomId).then(setSummary).catch(() => { /* 정원 기본 5명 */ }) }, [roomId])
  useEffect(() => { refreshSummary() }, [refreshSummary])

  const readyBox = me?.gift?.readyBoxId ?? null
  useEffect(() => {
    if (readyBox == null) return
    const k = `gyeol.giftShown.${roomId}.${readyBox}`
    try { if (sessionStorage.getItem(k)) return; sessionStorage.setItem(k, '1') } catch { /* 저장 못 해도 띄운다 */ }
    setSheet(prev => prev ?? { kind: 'gift', boxId: readyBox })
  }, [readyBox, roomId, setSheet])

  /** 내 노래가 바뀜: 지도·내 정보·판들을 다시 받는다 */
  const changed = useCallback(() => { setVersion(v => v + 1); refreshMap(true); refreshMe(); refreshSummary() }, [refreshMap, refreshMe, refreshSummary])
  const openSearch = useCallback((q: string) => { setQuery(q); setArtistPage(null); setSheet(null); setParams({ tab: 'search' }, { replace: true }) }, [setParams, setSheet])
  /** 4′2 지도 행성 → 가수 화면 (검색 탭 자리, PC는 오른쪽 판 검색) */
  const openArtistSongs = useCallback((artistId: number, name: string) => {
    const planet = map?.planets.find(p => p.artistId === artistId)
    setArtistPage({ artistId, name, fromPlanet: artistId, songCountInRoom: planet?.songs.length, onMap: true }); setSheet(null); setParams({ tab: 'search' }, { replace: true })
  }, [map, setParams, setSheet])

  // 지도 5초 새로고침 (탭이 보일 때만)
  useEffect(() => {
    const t = setInterval(() => { if (document.visibilityState === 'visible') refreshMap() }, 5000)
    return () => clearInterval(t)
  }, [refreshMap])

  const announceAdded = useCallback(async (song: SongView, sameSong: string[]) => {
    setAdded(prev => new Map(prev).set(`t${song.trackId}`, song.id))
    // 알림 문구는 담은 직후 지도로 정한다 (I-52 처음 보는 가수 = 이 곡의 행성이 준비 중). 지도는 어차피 다시 받는다
    setVersion(v => v + 1); refreshMe(); refreshSummary()
    const fresh = await refreshMap(true)
    const msg = addedMessage(song, sameSong, isNewArtist(fresh, song.trackId))
    show({
      title: msg.title, tone: msg.gold ? 'gold' : 'normal', cover: { roomId, coverPath: song.coverPath, coverUrl: song.coverUrl },
      sub: msg.sub ?? `${song.title} · ${song.artist}`,
      action: { label: '되돌리기', run: () => undo(song) },
    })
  }, [show, refreshMap, refreshMe, refreshSummary])

  async function undo(song: SongView) {
    try {
      await roomApi.removeSong(roomId, song.id)
      setAdded(prev => { const n = new Map(prev); n.delete(`t${song.trackId}`); return n })
      show({ title: '담은 노래를 뺐어요', sub: song.title })
      changed()
    } catch (err) {
      if (!handle(err)) show({ title: '빼지 못했어요', sub: err instanceof RoomApiError ? err.message : undefined, tone: 'error' })
    }
  }

  // I-51 상자 알림: 바로 담김(201)과 식별 끝(song-requests) 두 곳에서 같이 쓴다. 같은 상자는 한 번만
  const giftShown = useRef(new Set<number>())
  const notifyGift = useCallback((gift: GiftNotice | null | undefined) => {
    const t = giftNoticeToast(gift, giftShown.current)
    if (!t || !gift) return
    giftShown.current.add(gift.boxId)
    setTimeout(() => show({ ...t, tone: 'gold' }), 1500)
  }, [show])

  async function add(r: RoomSearchItem) {
    const key = itemKey(r.item)
    setBusy(prev => new Set(prev).add(key))
    try {
      const res = await roomApi.addSong(roomId, r.item)
      if (res.status === 'ADDED' && res.song) {
        setAdded(prev => new Map(prev).set(key, res.song!.id))
        announceAdded(res.song, r.sameSong)
        notifyGift(res.gift)
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
            else { announceAdded(r.song, p.sameSong); notifyGift(r.gift) }
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
  }, [pending, pollTick, roomId, show, announceAdded, notifyGift, handle])

  async function invite() {
    const url = `${location.origin}/rooms/${roomId}`
    const name = me?.roomName ?? '우리 우주'
    try {
      // 주소는 text 안 마지막 줄에만 (url 필드로 넘기면 공유 시트가 주소를 앞에 붙인다)
      const text = inviteMessage(name, url)
      if (navigator.share) { await navigator.share({ title: name, text }); return }
      await navigator.clipboard.writeText(text)
      show({ title: '초대 링크를 복사했어요', sub: '카톡에 붙여 넣어 보내 주세요' })
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      show({ title: '링크를 복사하지 못했어요', sub: url, tone: 'error' })
    }
  }

  const members = map?.members ?? []
  // 좁은 화면으로 바뀌거나 선택된 사람이 방을 나가면 푼다
  const memberIdsKey = members.map(m => m.id).join(',')
  useEffect(() => { if (!wide) setFocusId(null) }, [wide])
  useEffect(() => { setFocusId(cur => keepFocus(cur, memberIdsKey ? memberIdsKey.split(',').map(Number) : [])) }, [memberIdsKey])
  // Esc: 열린 판·대화상자가 있으면 그것부터 닫히게(Sheet가 Esc를 받는다) 두고, 없을 때만 선택을 푼다
  const sheetOpen = useRef(false)
  sheetOpen.current = sheet != null
  useEffect(() => {
    if (focusId == null) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !sheetOpen.current) setFocusId(null) }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [focusId])
  const isHost = me?.member.role === 'HOST'
  const badge = me?.gift ? giftLine(me.gift).badge : null
  const counts = mapCounts(map)

  const roomName = me?.roomName ?? keyStore.get(roomId)?.roomName ?? ''
  const goSearch = () => (wide ? setPanel('search') : setTab('search'))

  const pendingBar = pending.length > 0 && (
    <div className={sh.pending} role="status">
      <span className={s.spin} aria-hidden="true" style={{ display: 'inline-flex' }}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><path d="M12 3a9 9 0 1 0 9 9" /></svg>
      </span>
      <span>노래를 찾는 중이에요 · <strong>{pending[0].title}</strong>{pending.length > 1 ? ` 외 ${pending.length - 1}곡` : ''}</span>
    </div>
  )
  const notFoundBox = notFound && (
    <section className={sh.dialog} role="alertdialog" aria-labelledby="nf-title">
      <h2 id="nf-title">노래를 찾지 못해 담지 않았어요</h2>
      <p><strong>{notFound.title}</strong> · {notFound.text}</p>
      <div className={sh.dialogButtons}>
        <button type="button" className={s.primary} style={{ height: 44, fontSize: 14 }} onClick={() => { setQuery(notFound.title); setNotFound(null); goSearch() }}>다른 이름으로 찾기</button>
        <button type="button" className={s.secondary} style={{ height: 44, fontSize: 14 }} onClick={() => setNotFound(null)}>닫기</button>
      </div>
    </section>
  )
  // 지도 대신 목록 (11-14): 머리 ⋯ 메뉴와 지도 안 건너뛰기 링크가 같이 쓴다
  const [listView, setListView] = useState(false)
  const menuItems = [
    { label: listView ? '지도로 보기' : '목록으로 보기', onSelect: () => { setListView(v => !v); if (!wide) setTab('map') } },
    ...(isHost ? [{ label: '방 설정', onSelect: () => { refreshSummary(); setSheet({ kind: 'settings' }) } }] : []),
    { label: '내 정보', onSelect: () => setSheet({ kind: 'me' }) },
  ]
  // 건너뛰기 링크: 문서 맨 앞, 첫 Tab에서만 보인다(고정 위치). 지도 대신 목록으로
  const skipLink = map && map.planets.length > 0 && !listView && (
    <button type="button" className={lst.skip} onClick={() => { setListView(true); if (!wide) setTab('map') }}>지도 대신 목록으로 보기</button>
  )
  const galaxy = map
    ? <GalaxyView map={map} roomId={roomId} fresh={fresh} asList={listView} onAsList={setListView} meId={me?.member.id ?? -1} fill={wide} selectedId={wide && sheet?.kind === 'artist' ? sheet.id : null} onAddFirst={goSearch}
      focusId={wide ? focusId : null} onFocus={wide ? onFocus : undefined} onClearFocus={clearFocus} onSelectArtist={id => setSheet({ kind: 'artist', id })}
      onSelectGhost={(id, name) => { setCollapsed(false); setArtistPage({ artistId: id, name, fromMap: true, songCountInRoom: 0 }); setSheet(null); setParams({ tab: 'search' }, { replace: true }) }} />
    : <p className={sh.center} role="status">우주를 불러오는 중…</p>
  const pendingKeys = new Set(pending.map(p => p.key))
  /** 가수 화면에서 뒤로: 행성 판에서 왔으면 그 판으로(휴대폰은 지도 탭), 아니면 검색 결과로 */
  const backFromArtist = () => {
    const from = artistPage?.fromPlanet
    const fromMap = artistPage?.fromMap
    setArtistPage(null)
    if (fromMap) { if (!wide) setTab('map'); return }
    if (from == null) return
    if (!wide) setTab('map')
    setSheet({ kind: 'artist', id: from })
  }
  const searchPanel = artistPage ? (
    <ArtistPage key={`${artistPage.artistId ?? ''}|${artistPage.name}`} roomId={roomId} artist={artistPage} added={added}
      roomCount={artistPage.artistId != null && map ? (map.planets.find(p => p.artistId === artistPage.artistId)?.songs.length ?? 0) : undefined} pending={pendingKeys} busy={busy} onAdd={add}
      onError={handle} backLabel={artistPage.fromPlanet != null ? `${artistPage.name} 가수 판으로` : artistPage.fromMap ? '지도로' : '검색 결과로'} onBack={backFromArtist} />
  ) : (
    <SearchPanel roomId={roomId} map={map} query={query} setQuery={setQuery} added={added}
      pending={pendingKeys} busy={busy} onAdd={add} onOpenArtist={a => setArtistPage({ artistId: a.artistId, name: a.name, songCountInRoom: a.songCountInRoom, onMap: a.onMap })} />
  )
  const playTab = <PlayTab roomId={roomId} meId={me?.member.id ?? -1} version={version} onError={handle} show={show} />
  const mineTab = <MineTab roomId={roomId} me={me} map={map} version={version} onChanged={changed} onError={handle} show={show} onOpenGift={boxId => setSheet({ kind: 'gift', boxId })} onOpenArtist={id => { if (!wide) setTab('map'); setSheet({ kind: 'artist', id }) }} />
  const peopleSheet = (
    <PeopleSheet roomId={roomId} map={map} meId={me?.member.id ?? -1} capacity={summary?.capacity ?? 5} version={version} isHost={isHost}
      onClose={() => setSheet(null)} onInvite={invite} onError={handle} focusId={focusId} onFocus={wide ? onFocus : undefined}
      onOpenMe={() => setSheet({ kind: 'me' })} onManage={m => setSheet({ kind: 'member', member: m })} onSettings={() => { refreshSummary(); setSheet({ kind: 'settings' }) }} />
  )
  const meSheet = sheet?.kind === 'me' && me && (
    <MeSheet roomId={roomId} me={me} onClose={() => setSheet(wide ? null : { kind: 'people' })} onRenamed={changed} onError={handle} show={show}
      onLeft={() => { keyStore.remove(roomId); onLostKey() }} />
  )
  const artistSheet = sheet?.kind === 'artist' && (
    <ArtistSheet roomId={roomId} artistId={sheet.id} map={map} meId={me?.member.id ?? -1} version={version}
      onClose={() => setSheet(null)} onSearch={openSearch} onChanged={changed} onError={handle} show={show}
      onSelectArtist={id => setSheet({ kind: 'artist', id })} onOpenSongs={openArtistSongs} />
  )
  // 방 위에 뜨는 것: 휴대폰은 아래 판, PC는 가운데 대화상자
  const overlays = (
    <>
      {sheet?.kind === 'member' && (
        <MemberSheet roomId={roomId} member={sheet.member} onClose={() => setSheet({ kind: 'people' })} onError={handle} show={show}
          onKicked={() => { changed(); setSheet({ kind: 'people' }) }} />
      )}
      {sheet?.kind === 'gift' && me && (
        <GiftSheet roomId={roomId} boxId={sheet.boxId} me={me} onClose={() => { setSheet(null); changed() }} onAvatarChanged={changed} onError={handle} show={show} />
      )}
      {sheet?.kind === 'settings' && summary && (
        <SettingsSheet roomId={roomId} summary={summary} memberCount={summary.members.length} songCount={counts.songs}
          onClose={() => setSheet({ kind: 'people' })} onChanged={changed} onError={handle} show={show}
          onDeleted={() => { keyStore.remove(roomId); navigate('/', { replace: true }) }} />
      )}
    </>
  )

  if (wide) {
    const detail = artistSheet || meSheet
    const PANEL_LABEL: Record<PanelTab, string> = { play: '놀이터', search: '검색', mine: '내 노래', people: '참가자' }
    // 결 전용 아이콘 (11-14): 놀이터 = 겹친 두 행성, 검색 = 망원경, 내 노래 = 내 색 레코드판, 참가자 = 궤도 도는 점들
    const myColor = me ? memberColor(me.member.colorIdx).bg : undefined
    const PANEL_ICON: Record<PanelTab, ReactNode> = { play: <PlayIcon />, search: <ScopeIcon />, mine: <RecordIcon color={myColor} />, people: <PeopleIcon /> }
    return (
      <AvatarRoom.Provider value={roomId}>
      <div className={sh.wideShell}>
        {skipLink}
        <header className={sh.wideHeader}>
          <Link to="/" aria-label="첫 화면"><Wordmark size={22} /></Link>
          <RoomPlanet colors={members.map(m => memberColor(m.colorIdx).bg)} />
          <h1 className={sh.wideRoomName}>{roomName}</h1>
          <span className={sh.counts}>{counts.songs === 0 ? '아직 비어 있어요' : `가수 ${counts.artists}명 · 노래 ${counts.songs}곡`}</span>
          <button type="button" className={sh.faces} style={{ marginLeft: 'auto' }} aria-label={`참가자 ${members.length}명 보기`} onClick={() => setPanel('people')}>
            {members.map(m => <span key={m.id} className={sh.faceRing} style={{ ['--ring' as string]: memberColor(m.colorIdx).bg }}><Avatar nickname={m.nickname} colorIdx={m.colorIdx} characterId={m.avatarCharacterId} size={34} /></span>)}
          </button>
          <button type="button" className={sh.ghostPill} onClick={invite}>링크 복사</button>
          <button type="button" className={sh.accentPill} onClick={() => setPanel('search')}>+ 노래 담기</button>
          <HeadMenu className={sh.moreButton} items={menuItems} />
        </header>
        <div className={sh.wideBody}>
          <section className={sh.mapArea} aria-label="우리 지도">
            {galaxy}
            {(pendingBar || notFoundBox) && <div className={sh.mapNotices}>{pendingBar}{notFoundBox}</div>}
            <Toast toast={toast} onClose={closeToast} wide />
            {/* 판 왼쪽 가장자리(= 지도 오른쪽 끝)에 붙은 접기 손잡이. 판은 스크롤 상자라 그 안에 두면 잘린다 */}
            {!collapsed && (
              <button type="button" className={sh.panelToggle} aria-expanded={true} aria-label="오른쪽 판 접기" title="판 접기" onClick={() => setCollapsed(true)}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
              </button>
            )}
          </section>
          {collapsed ? (
            <aside className={`${sh.sidePanel} ${sh.sideRail}`} aria-label="자세히 (접힘)">
              <button type="button" className={sh.railToggle} aria-expanded={false} aria-label="오른쪽 판 펼치기" title="판 펼치기" onClick={() => setCollapsed(false)}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 6l-6 6 6 6" /></svg>
              </button>
              <nav className={sh.railTabs} aria-label="판 보기">
                {PANEL_TABS.map(t => {
                  const count = t === 'mine' ? me?.songCount : t === 'people' ? members.length : undefined
                  const label = `${PANEL_LABEL[t]}${count != null ? ` ${count}` : ''}${t === 'mine' && badge ? ' · 선물 소식 있음' : ''}`
                  return (
                    <button key={t} type="button" className={sh.railTab} aria-label={`${label} 열기`} title={PANEL_LABEL[t]} aria-current={panelTab === t ? 'page' : undefined} onClick={() => setPanel(t)}>
                      {PANEL_ICON[t]}
                      {t === 'mine' && badge ? <span className={sh.railBadge} aria-hidden="true">{badge}</span>
                        : count != null && <span className={sh.railCount} aria-hidden="true">{count}</span>}
                    </button>
                  )
                })}
              </nav>
            </aside>
          ) : (
          <aside className={sh.sidePanel} aria-label="자세히">
            <nav className={sh.segTabs} aria-label="판 보기">
              {PANEL_TABS.map(t => (
                <button key={t} type="button" className={sh.segTab} aria-current={!detail && panelTab === t ? 'page' : undefined} onClick={() => setPanel(t)}>
                  {PANEL_LABEL[t]}
                  {t === 'mine' && me && <span className={sh.segCount}>{me.songCount}</span>}
                  {t === 'people' && <span className={sh.segCount}>{members.length}</span>}
                  {t === 'mine' && badge && <><span className={sh.segBadge} aria-hidden="true">{badge}</span><span className={s.srOnly}> · 선물 소식 있음</span></>}
                </button>
              ))}
            </nav>
            <SheetModeContext.Provider value="panel">
              {detail}
            </SheetModeContext.Provider>
            {!detail && (
              <div className={sh.panelBody}>
                {panelTab === 'search' && searchPanel}
                {panelTab === 'play' && playTab}
                {panelTab === 'mine' && mineTab}
                {panelTab === 'people' && <SheetModeContext.Provider value="tab">{peopleSheet}</SheetModeContext.Provider>}
              </div>
            )}
          </aside>
          )}
        </div>
        <SheetModeContext.Provider value="dialog">{overlays}</SheetModeContext.Provider>
      </div>
      </AvatarRoom.Provider>
    )
  }

  return (
    <AvatarRoom.Provider value={roomId}>
    <div className={sh.shell}>
      {skipLink}
      <header className={sh.header}>
        <div className={sh.headRow}>
          <Link to="/" aria-label="첫 화면"><Wordmark size={20} /></Link>
          <div className={sh.headRight}>
            <button type="button" className={sh.faces} aria-label={`참가자 ${members.length}명 보기`} onClick={() => setSheet({ kind: 'people' })}>
              {members.map(m => <span key={m.id} className={sh.faceRing} style={{ ['--ring' as string]: memberColor(m.colorIdx).bg }}><Avatar nickname={m.nickname} colorIdx={m.colorIdx} characterId={m.avatarCharacterId} size={32} /></span>)}
            </button>
            <button type="button" className={sh.iconButton} aria-label="친구 초대하기" onClick={invite}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 15V3" /><path d="M7 8l5-5 5 5" /><path d="M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5" /></svg>
            </button>
            <HeadMenu className={sh.iconButton} items={menuItems} />
          </div>
        </div>
        <div className={sh.titleRow}>
          <h1 className={sh.roomName}><RoomPlanet colors={members.map(m => memberColor(m.colorIdx).bg)} size={22} />{roomName}</h1>
          <span className={sh.counts}>노래 {counts.songs}곡 · 가수 {counts.artists}명</span>
        </div>
      </header>

      <main className={sh.body}>
        {pendingBar}
        {notFoundBox}
        {tab === 'map' && galaxy}
        {tab === 'search' && searchPanel}
        {tab === 'play' && playTab}
        {tab === 'mine' && mineTab}
      </main>

      {sheet?.kind === 'people' && peopleSheet}
      {meSheet}
      {overlays}
      {artistSheet}

      <Toast toast={toast} onClose={closeToast} />

      <nav className={sh.tabs} aria-label="방 메뉴">
        {TABS.map(t => (
          <button key={t.id} type="button" className={sh.tab} aria-current={tab === t.id ? 'page' : undefined} onClick={() => setTab(t.id)}>
            {t.id === 'play' ? <PlayIcon size={24} /> : t.id === 'search' ? <ScopeIcon size={24} /> : t.id === 'mine' ? <RecordIcon size={24} color={me ? memberColor(me.member.colorIdx).bg : undefined} />
              : <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{t.icon}</svg>}
            {t.label}
            {t.id === 'mine' && badge && <><span className={sh.tabBadge} aria-hidden="true">{badge}</span><span className={s.srOnly}> · 선물 소식 있음</span></>}
          </button>
        ))}
      </nav>
    </div>
    </AvatarRoom.Provider>
  )
}
