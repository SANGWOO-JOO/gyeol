import { useEffect, useRef, useState } from 'react'
import { roomApi, RoomApiError, type RoomSearchItem, type TrackItem } from '../roomApi'
import { searchBadge } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import sh from './shell.module.css'

/** 검색 결과 한 줄을 구분하는 키 (DB 곡은 trackId, 아니면 제목|가수) */
export const itemKey = (t: TrackItem) => (t.trackId != null ? `t${t.trackId}` : `q${t.title}|${t.artist}`.toLowerCase())

/** 4-d 노래 검색해 담기. 담는 일은 부모(RoomShell)가 하고, 여기서는 찾기와 표시만 */
export function SearchPanel({
  roomId, query, setQuery, added, pending, busy, onAdd,
}: {
  roomId: string
  query: string
  setQuery: (q: string) => void
  /** 이번에 담은 곡 키 → 담긴 곡 id (되돌리기 반영) */
  added: Map<string, number>
  /** 찾는 중인 곡 키 */
  pending: Set<string>
  /** 담기 요청 중인 곡 키 */
  busy: Set<string>
  onAdd: (r: RoomSearchItem) => void
}) {
  const [items, setItems] = useState<RoomSearchItem[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => { inputRef.current?.focus() }, [])

  useEffect(() => {
    const q = query.trim()
    if (q.length < 1) { setItems(null); setError(null); setLoading(false); return }
    const ac = new AbortController()
    setLoading(true)
    const t = setTimeout(() => {
      roomApi.search(roomId, q, ac.signal)
        .then(r => { setItems(r.items); setError(null) })
        .catch(err => { if (!ac.signal.aborted) setError(err instanceof RoomApiError ? err.message : '검색하지 못했어요') })
        .finally(() => { if (!ac.signal.aborted) setLoading(false) })
    }, 300)
    return () => { clearTimeout(t); ac.abort() }
  }, [roomId, query])

  return (
    <section aria-label="노래 검색" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <label className={sh.searchBox}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#B7AFD1" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
        <input ref={inputRef} type="search" aria-label="노래 검색" placeholder="노래나 가수 이름" value={query} onChange={e => setQuery(e.target.value)} enterKeyHint="search" />
        {query && (
          <button type="button" className={sh.clear} aria-label="검색어 지우기" onClick={() => { setQuery(''); inputRef.current?.focus() }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        )}
      </label>

      <div className={sh.metaRow}>
        <span aria-live="polite">{loading ? '찾는 중…' : items ? `검색 결과 ${items.length}곡` : '좋아하는 노래를 찾아 담아요'}</span>
        <span>담는 즉시 우주에 떠요</span>
      </div>

      {error && <p className={sh.center} role="alert" style={{ color: 'var(--r-error)' }}>{error}</p>}

      {items && items.length === 0 && !loading && (
        <p className={sh.center}>찾는 노래가 없어요. 영문·원어 이름으로도 찾아보세요</p>
      )}

      {items && items.length > 0 && (
        <ul className={sh.list}>
          {items.map(r => {
            const key = itemKey(r.item)
            const isAdded = r.mine || added.has(key)
            return (
              <SearchRow key={key} r={r} added={isAdded} pending={pending.has(key)} busy={busy.has(key)} onAdd={() => onAdd(r)} />
            )
          })}
        </ul>
      )}

      {items && items.length > 0 && <span className={sh.center}>찾는 노래가 없으면 영문·원어 이름으로도 찾아보세요</span>}
    </section>
  )
}

function SearchRow({ r, added, pending, busy, onAdd }: { r: RoomSearchItem; added: boolean; pending: boolean; busy: boolean; onAdd: () => void }) {
  const t = r.item
  const badge = searchBadge(r)
  const sub = [t.artist, t.releaseYear].filter(Boolean).join(' · ')
  return (
    <li className={sh.row}>
      {t.coverUrl ? <img src={t.coverUrl} alt="" className={sh.cover} loading="lazy" /> : <span className={sh.cover} aria-hidden="true" />}
      <span className={sh.rowText}>
        <span className={sh.songTitle}>{t.title}</span>
        <span className={sh.songSub}>{sub}</span>
        {badge.kind === 'sameSong' && <span className={`${sh.badge} ${sh.badgeGold}`}>{badge.text}</span>}
        {badge.kind === 'sameArtist' && <span className={`${sh.badge} ${sh.badgeFriend}`}>{badge.text}</span>}
        {badge.kind === 'identify' && !added && <span className={`${sh.badge} ${sh.badgeGold}`}>{badge.text}</span>}
      </span>
      {added ? (
        <span className={sh.added}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12l5 5 9-10" /></svg>
          담았어요
        </span>
      ) : pending ? (
        <span className={sh.added}><Spinner size={14} /> 찾는 중</span>
      ) : badge.kind === 'sameSong' ? (
        <button type="button" className={`${sh.add} ${sh.addGold}`} disabled={busy} onClick={onAdd} aria-label={`${t.title} 나도 담기`}>
          <svg width="13" height="13" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2l2.4 7.6L22 12l-7.6 2.4L12 22l-2.4-7.6L2 12l7.6-2.4z" fill="currentColor" /></svg>
          나도 담기
        </button>
      ) : (
        <button type="button" className={sh.add} disabled={busy} onClick={onAdd} aria-label={`${t.title} 담기`}>{busy ? <Spinner size={14} /> : '담기'}</button>
      )}
    </li>
  )
}
