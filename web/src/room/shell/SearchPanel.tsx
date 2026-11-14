import { useEffect, useRef, useState } from 'react'
import { memberColor, roomApi, RoomApiError, type MemberView, type RoomMap, type RoomSearchItem, type SearchArtist } from '../roomApi'
import { searchable, searchDelay, appendSearch, artistCardSub, artistDisplayName, artistFaceUrl, searchBadge, showArtistMore, sharedWithText, trackKey } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import { ArtistFace } from './ArtistFace'
import { AllSongsButton } from './AllSongsButton'
import { genreColor } from '../galaxy/faces'
import { CoverImg } from './CoverImg'
import sh from './shell.module.css'
import { Avatar } from '../components/Avatar'
import { sourcesOf, useImageChain } from '../images'
import { planetFace } from '../galaxy/faces'
import { pushRecent, readRecent, topArtists, writeRecent } from './tabLogic'

/** 검색 결과 한 줄을 구분하는 키 (DB 곡은 trackId, 아니면 제목|가수) */
export const itemKey = trackKey

/** 4-d 노래 검색해 담기. 담는 일은 부모(RoomShell)가 하고, 여기서는 찾기와 표시만 */
export function SearchPanel({
  roomId, query, setQuery, added, pending, busy, onAdd, onOpenArtist, map = null,
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
  /** 가수 카드 → 가수 화면 (I-30) */
  onOpenArtist: (a: SearchArtist) => void
  /** 지도(사람 색·우리 우주에 많은 가수) */
  map?: RoomMap | null
}) {
  const members = map?.members ?? []
  const [recent, setRecent] = useState<string[]>(readRecent)
  const [items, setItems] = useState<RoomSearchItem[] | null>(null)
  const [rawArtist, setArtist] = useState<SearchArtist | null>(null)
  // 표시 이름은 DB 이름(displayName, 지도와 같음): 카드·목록 끝 버튼·가수 화면 제목이 모두 이 name을 쓴다
  const artist = rawArtist ? { ...rawArtist, name: artistDisplayName(rawArtist) } : null
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 오류 뒤 같은 검색어로 다시 (I-42 D): Enter 또는 [다시 시도]
  const [retry, setRetry] = useState(0)
  const [composing, setComposing] = useState(false)
  // 더보기: 받은 쪽·다음 쪽 있음·이어서 받는 중·이어 받기 오류. 검색어가 바뀌면 처음부터
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreError, setMoreError] = useState<string | null>(null)
  const searchGen = useRef(0)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => { inputRef.current?.focus() }, [])

  useEffect(() => {
    const q = query.trim()
    const gen = ++searchGen.current
    setPage(1); setHasMore(false); setLoadingMore(false); setMoreError(null)
    // 한글 조합 중(ㅂ→비)에는 보내지 않는다: 조합이 끝나면 다시 이 효과가 돈다
    if (composing) return
    if (!searchable(q)) { setItems(null); setArtist(null); setError(null); setLoading(false); return }
    const ac = new AbortController()
    setLoading(true)
    const t = setTimeout(() => {
      roomApi.search(roomId, q, ac.signal)
        .then(r => {
          if (gen !== searchGen.current) return
          setItems(r.items); setArtist(r.artist ?? null); setPage(r.page ?? 1); setHasMore(!!r.hasMore); setError(null)
          // 결과가 있는 검색어만 최근 검색어로
          if (r.items.length > 0 || r.artist) setRecent(prev => { const next = pushRecent(prev, q); writeRecent(next); return next })
        })
        .catch(err => { if (!ac.signal.aborted) setError(err instanceof RoomApiError ? err.message : '검색하지 못했어요') })
        .finally(() => { if (!ac.signal.aborted) setLoading(false) })
    }, searchDelay(q))
    return () => { clearTimeout(t); ac.abort() }
  }, [roomId, query, retry, composing])

  async function more() {
    const q = query.trim()
    if (!q || loadingMore || !hasMore) return
    const gen = searchGen.current
    setLoadingMore(true); setMoreError(null)
    try {
      const r = await roomApi.search(roomId, q, undefined, page + 1)
      if (gen !== searchGen.current) return // 그사이 검색어가 바뀜
      // 2쪽부터 artist는 null: 1쪽 가수 카드를 그대로 둔다
      setItems(prev => appendSearch(prev ?? [], r.items))
      setPage(r.page ?? page + 1); setHasMore(!!r.hasMore && r.items.length > 0) // 빈 쪽이 오면(1쪽 hasMore는 추정값) 더보기를 숨긴다
    } catch (err) {
      if (gen === searchGen.current) setMoreError(err instanceof RoomApiError ? err.message : '더 불러오지 못했어요')
    } finally {
      if (gen === searchGen.current) setLoadingMore(false)
    }
  }

  return (
    <section aria-label="노래 검색" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div className={sh.searchSticky}>
      <label className={sh.searchBox}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#B7AFD1" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
        <input ref={inputRef} type="search" aria-label="노래 검색" placeholder="노래나 가수 이름" value={query} onChange={e => setQuery(e.target.value)} enterKeyHint="search"
          onCompositionStart={() => setComposing(true)} onCompositionEnd={e => { setComposing(false); setQuery(e.currentTarget.value) }}
          onKeyDown={e => { if (e.key === 'Enter' && searchable(query)) { e.preventDefault(); setRetry(n => n + 1) } }} />
        {query && (
          <button type="button" className={sh.clear} aria-label="검색어 지우기" onClick={() => { setQuery(''); inputRef.current?.focus() }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        )}
      </label>
      </div>

      {!searchable(query) && !items && (
        <SearchIdle roomId={roomId} map={map} recent={recent} onPick={q => setQuery(q)} onClear={() => { setRecent([]); writeRecent([]) }} />
      )}

      {items && <div className={sh.metaRow}>
        <span aria-live="polite">{loading ? '찾는 중…' : items ? `검색 결과 ${items.length}곡` : '좋아하는 노래를 찾아 담아요'}</span>
        <span>담는 즉시 우주에 떠요</span>
      </div>}

      {error && (
        <p className={sh.center} role="alert" style={{ color: 'var(--r-error)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10, flexWrap: 'wrap' }}>
          {error}
          <button type="button" className={sh.sortChip} onClick={() => setRetry(n => n + 1)}>다시 시도</button>
        </p>
      )}

      {artist && !loading && (
        <>
          <span className={sh.groupLabel}>가수</span>
          <ArtistCard roomId={roomId} artist={artist} onOpen={() => onOpenArtist(artist)} note={friendsNote(items)} />
          {items && items.length > 0 && <span className={sh.groupLabel}>노래</span>}
        </>
      )}

      {items && items.length === 0 && !loading && !artist && (
        <p className={sh.center}>찾는 노래가 없어요. 영문·원어 이름으로도 찾아보세요</p>
      )}

      {items && items.length > 0 && (
        <ul className={sh.songList}>
          {items.map(r => {
            const key = itemKey(r.item)
            const isAdded = r.mine || added.has(key)
            return (
              <SearchRow key={key} roomId={roomId} r={r} added={isAdded} pending={pending.has(key)} busy={busy.has(key)} onAdd={() => onAdd(r)} members={members} />
            )
          })}
        </ul>
      )}

      {items && items.length > 0 && !loading && (hasMore || moreError) && (
        <div className={sh.moreRow}>
          {moreError && <span role="alert">{moreError}</span>}
          <button type="button" className={sh.searchMore} onClick={more} disabled={loadingMore} aria-busy={loadingMore}>
            {loadingMore ? <><Spinner size={14} /> 불러오는 중…</> : moreError ? '다시 시도' : '더보기'}
          </button>
        </div>
      )}
      {/* 목록 끝: 맨 위 가수 카드가 스크롤로 사라져도 가수 화면(인기순·최신순·더보기)으로 갈 수 있게 */}
      {showArtistMore(artist, items, loading) && artist && (
        <AllSongsButton roomId={roomId} name={artist.name} covers={(items ?? []).map(r => r.item)} color={artist.face?.galaxyId != null ? genreColor(null, artist.face.galaxyId) : '#8e7bd0'}
          sub="인기순 · 최신순" ariaLabel={`${artist.name} 노래 전체 보기, 인기순·최신순`} onClick={() => onOpenArtist(artist)} />
      )}
      {items && items.length > 0 && <span className={sh.center}>찾는 노래가 없으면 영문·원어 이름으로도 찾아보세요</span>}
    </section>
  )
}

/** 4-d2 검색 맨 위 가수 카드: 누르면 가수 화면(노래 전체 보기) */
function ArtistCard({ roomId, artist, onOpen, note }: { roomId: string; artist: SearchArtist; onOpen: () => void; note?: string | null }) {
  const sub = artistCardSub(artist)
  return (
    <button type="button" className={sh.artistCard} onClick={onOpen} aria-label={`${artist.name} 노래 전체 보기`}>
      {/* 얼굴: 응답 face(PHOTO 사진 · COVER 방 곡 표지 · NONE 이니셜). 카드 전체가 버튼이라 ⓘ 출처 버튼은 넣지 않는다(출처는 가수 화면에서) */}
      <ArtistFace roomId={roomId} name={artist.name} className={sh.artistOrb} photoUrl={artistFaceUrl(artist)} />
      <span className={sh.rowText}>
        <strong className={sh.artistName}>{artist.name}</strong>
        {sub && <span className={sh.songSub}>{sub}</span>}
        {note && <span className={sh.cardNote}>{note}</span>}
      </span>
      <span className={sh.artistMore} aria-hidden="true">
        노래 전체 보기
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 6l6 6-6 6" /></svg>
      </span>
    </button>
  )
}

/** 노래 한 줄 (검색 결과·가수 화면 같이 씀). rank는 인기순 순위, sub를 주면 둘째 줄을 바꾼다 */
export function SearchRow({ roomId, r, added, pending, busy, onAdd, rank, sub: subOverride, addedLabel = '담았어요', goldRing = false, members = [] }: {
  roomId: string; r: RoomSearchItem; added: boolean; pending: boolean; busy: boolean; onAdd: () => void
  /** 같이 담은 사람 얼굴 칩용 (닉네임 → 사람 색) */
  members?: MemberView[]
  rank?: number | null; sub?: string; addedLabel?: string
  /** 가수 화면: 친구가 담은 곡 줄에 금빛 테두리 */
  goldRing?: boolean
}) {
  const t = r.item
  const badge = searchBadge(r)
  const sub = subOverride ?? [t.artist, t.releaseYear].filter(Boolean).join(' · ')
  return (
    <li className={`${sh.row} ${(goldRing || badge.kind === 'sameSong') && r.sameSong.length > 0 ? sh.rowGold : ''}`}>
      {rank != null && <span className={sh.rank}>{rank}</span>}
      <CoverImg roomId={roomId} cover={t} className={sh.cover} />
      <span className={sh.rowText}>
        <span className={sh.songTitle}>{t.title}</span>
        {sub && <span className={sh.songSub}>{sub}</span>}
        {badge.kind === 'sameSong' && <FaceChips names={r.sameSong} members={members} text={badge.text} />}
        {goldRing && badge.kind === 'mine' && sharedWithText(r.sameSong) && <span className={`${sh.badge} ${sh.badgeGold}`}>{sharedWithText(r.sameSong)}</span>}
        {/* "○○님과 같은 아티스트"는 줄마다 반복돼 시끄러워 가수 카드에 한 번만 (11-14) */}
      </span>
      {added ? (
        <span className={sh.added}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12l5 5 9-10" /></svg>
          {addedLabel}
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

/** 같이 담은 사람 작은 얼굴 칩 + 금빛 글자 (닉네임으로 사람 색을 찾는다) */
export function FaceChips({ names, members, text }: { names: string[]; members: MemberView[]; text: string }) {
  return (
    <span className={sh.faceChips}>
      <span aria-hidden="true">
        {names.slice(0, 3).map(n => { const m = members.find(x => x.nickname === n); return <Avatar key={n} nickname={n} colorIdx={m?.colorIdx ?? 0} characterId={m?.avatarCharacterId} size={18} /> })}
      </span>
      {text}
    </span>
  )
}

/** 가수 카드에 한 번: 이 가수를 담은 친구들 (검색 결과 곡의 같은 노래·같은 가수를 합쳐서) */
function friendsNote(items: RoomSearchItem[] | null): string | null {
  const set = new Set<string>()
  for (const r of items ?? []) { r.sameArtist.forEach(n => set.add(n)); r.sameSong.forEach(n => set.add(n)) }
  const list = [...set]
  if (list.length === 0) return null
  return `${list.length <= 2 ? list.map(n => `${n}님`).join('·') : `${list[0]}님 외 ${list.length - 1}명`}도 이 가수를 담았어요`
}

/** 검색어가 없을 때: 우리 우주에 많은 가수 얼굴 칩(누르면 그 이름으로 검색) · 최근 검색어 */
function SearchIdle({ roomId, map, recent, onPick, onClear }: { roomId: string; map: RoomMap | null; recent: string[]; onPick: (q: string) => void; onClear: () => void }) {
  const top = topArtists(map, 8)
  return (
    <div className={sh.searchIdle}>
      {top.length > 0 && (
        <section aria-label="우리 우주에 많은 가수">
          <h3 className={sh.idleHead}>우리 우주에 많은 가수</h3>
          <div className={sh.idleArtists}>
            {top.map(a => (
              <button key={a.artistId} type="button" className={sh.idleArtist} onClick={() => onPick(a.name)} aria-label={`${a.name} 찾기, 우리 우주에 ${a.count}곡`}>
                <IdleFace roomId={roomId} name={a.name} face={a.face} />
                <span>{a.name}</span>
              </button>
            ))}
          </div>
        </section>
      )}
      {recent.length > 0 && (
        <section aria-label="최근 검색어">
          <h3 className={sh.idleHead}>최근 검색어 <button type="button" onClick={onClear}>모두 지우기</button></h3>
          <div className={sh.recents}>
            {recent.map(q => (
              <button key={q} type="button" className={sh.recentChip} onClick={() => onPick(q)}>
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></svg>
                {q}
              </button>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

function IdleFace({ roomId, name, face }: { roomId: string; name: string; face: RoomMap['planets'][number]['face'] }) {
  const fc = planetFace({ songs: [], face })
  const { src, fail } = useImageChain(roomId, fc.kind === 'genre' ? sourcesOf([]) : fc.sources)
  return (
    <span className={sh.idleFace} aria-hidden="true" style={src ? undefined : { background: `radial-gradient(120% 120% at 30% 25%, ${memberColor(0).bg}, #2e2760 85%)` }}>
      {src ? <img src={src} alt="" onError={fail} /> : Array.from(name)[0]}
    </span>
  )
}
