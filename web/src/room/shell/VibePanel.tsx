import { useEffect, useRef, useState } from 'react'
import { roomApi, type SearchArtist, type VibePick, type VibeSearchView } from '../roomApi'
import { sourceName } from '../roomLogic'
import { inRoomText, vibeErrorText, vibeLength, vibeQueryOk, VIBE_MAX, VIBE_POLL_MAX, VIBE_POLL_MS } from '../vibeLogic'
import { Spinner } from '../components/RoomPage'
import { ArtistFace } from './ArtistFace'
import sh from './shell.module.css'

/** 가수 화면에 갔다 돌아와도 마지막 질문·결과가 남게 방마다 기억 (새로고침하면 비움) */
const memo = new Map<string, { q: string; view: VibeSearchView | null }>()

const EXAMPLES = ['비 오는 밤 몽환적인 밴드', '출근길에 듣는 신나는 시티팝', '새벽 감성 잔잔한 어쿠스틱']

/**
 * 분위기로 가수 찾기 (vibe-search-rag.md 9절). 문장 → POST → DONE/FAILED까지 1초마다 GET(코드 맞추기와 같은 방식)
 * → 가수 카드 3~5개(사진·이유·근거 출처·방에서 담은 사람). 카드를 누르면 기존 가수 화면으로.
 */
export function VibePanel({ roomId, onOpenArtist }: { roomId: string; onOpenArtist: (a: SearchArtist) => void }) {
  const saved = memo.get(roomId)
  const [q, setQ] = useState(saved?.q ?? '')
  const [view, setView] = useState<VibeSearchView | null>(saved?.view ?? null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [tries, setTries] = useState(0)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => { memo.set(roomId, { q, view }) }, [roomId, q, view])
  useEffect(() => { if (!saved?.view) inputRef.current?.focus() }, [])

  // PENDING이면 1초마다 확인
  const pending = view?.status === 'PENDING'
  useEffect(() => {
    if (!view || view.status !== 'PENDING') return
    if (tries >= VIBE_POLL_MAX) return
    const ac = new AbortController()
    const t = setTimeout(() => {
      roomApi.vibeSearchGet(roomId, view.id, ac.signal)
        .then(v => { setView(v); setTries(n => n + 1) })
        .catch(err => { if (!ac.signal.aborted) { setError(vibeErrorText(err)); setView(null) } })
    }, VIBE_POLL_MS)
    return () => { clearTimeout(t); ac.abort() }
  }, [roomId, view, tries])
  const timedOut = pending && tries >= VIBE_POLL_MAX

  const len = vibeLength(q)
  const ok = vibeQueryOk(q)

  async function run() {
    if (!ok || busy) return
    setBusy(true); setError(null); setTries(0)
    try {
      setView(await roomApi.vibeSearch(roomId, q.trim()))
    } catch (err) {
      setError(vibeErrorText(err)); setView(null)
    } finally {
      setBusy(false)
    }
  }

  const working = busy || (pending && !timedOut)
  return (
    <section aria-label="분위기로 가수 찾기" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <form className={sh.vibeForm} onSubmit={e => { e.preventDefault(); run() }}>
        <textarea ref={inputRef} aria-label="찾고 싶은 분위기" rows={2} maxLength={VIBE_MAX + 20} value={q} placeholder="듣고 싶은 분위기를 문장으로 적어요"
          onChange={e => setQ(e.target.value)} enterKeyHint="search"
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); run() } }} />
        <div className={sh.vibeFoot}>
          <span className={len > VIBE_MAX ? sh.vibeOver : undefined} aria-live="polite">{len}/{VIBE_MAX}</span>
          <button type="submit" className={sh.add} disabled={!ok || working}>{working ? <Spinner size={14} /> : '찾기'}</button>
        </div>
      </form>

      {!view && !error && !busy && (
        <div className={sh.recents} aria-label="이런 문장은 어때요">
          {EXAMPLES.map(x => <button key={x} type="button" className={sh.recentChip} onClick={() => setQ(x)}>{x}</button>)}
        </div>
      )}

      {error && (
        <p className={sh.center} role="alert" style={{ color: 'var(--r-error)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10, flexWrap: 'wrap' }}>
          {error}
        </p>
      )}

      {working && view && <p className={sh.center} role="status"><Spinner size={12} /> 가수 설명을 읽고 결이 맞는 가수를 고르는 중이에요…</p>}
      {timedOut && (
        <p className={sh.center} role="alert" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10, flexWrap: 'wrap' }}>
          생각보다 오래 걸려요
          <button type="button" className={sh.sortChip} onClick={() => setTries(0)}>다시 확인</button>
        </p>
      )}
      {view?.status === 'FAILED' && (
        <p className={sh.center} role="alert" style={{ color: 'var(--r-error)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10, flexWrap: 'wrap' }}>
          가수를 찾지 못했어요
          <button type="button" className={sh.sortChip} onClick={run}>다시 시도</button>
        </p>
      )}
      {view?.status === 'DONE' && <VibeResult roomId={roomId} view={view} onOpenArtist={onOpenArtist} />}
    </section>
  )
}

function VibeResult({ roomId, view, onOpenArtist }: { roomId: string; view: VibeSearchView; onOpenArtist: (a: SearchArtist) => void }) {
  if (view.notFound || view.picks.length === 0) {
    return <p className={sh.center}>맞는 가수를 못 찾았어요. 다른 말로 적어 보세요</p>
  }
  return (
    <>
      <span className={sh.groupLabel} aria-live="polite">“{view.query}”에 어울리는 가수 {view.picks.length}명</span>
      <ul className={sh.vibeList}>
        {view.picks.map(p => <li key={p.artistId}><VibeCard roomId={roomId} p={p} onOpen={() => onOpenArtist({ artistId: p.artistId, name: p.name })} /></li>)}
      </ul>
      {view.evidence.length > 0 && (
        <section className={sh.why}>
          <h3>이유의 근거</h3>
          <div className={sh.evidence}>
            {view.evidence.map((e, i) => <a key={e.id} href={e.url} target="_blank" rel="noreferrer noopener">근거 {i + 1} · {sourceName(e.source)}</a>)}
          </div>
        </section>
      )}
    </>
  )
}

function VibeCard({ roomId, p, onOpen }: { roomId: string; p: VibePick; onOpen: () => void }) {
  const note = inRoomText(p.inRoomBy)
  return (
    <button type="button" className={`${sh.artistCard} ${sh.vibeCard}`} onClick={onOpen} aria-label={`${p.name} 노래 전체 보기`}>
      <ArtistFace roomId={roomId} name={p.name} className={sh.artistOrb} photoUrl={p.imageUrl} />
      <span className={sh.rowText}>
        <strong className={sh.artistName}>{p.name}</strong>
        {p.reason && <span className={sh.vibeReason}>{p.reason}</span>}
        {note && <span className={sh.cardNote}>{note}</span>}
      </span>
      <svg className={sh.vibeChevron} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
    </button>
  )
}
