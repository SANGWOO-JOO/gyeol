import { useEffect, useState } from 'react'
import { memberColor, roomApi, type MemberRow, type RoomMap } from '../roomApi'
import { genreColor, planetFace } from '../galaxy/faces'
import { useImageChain } from '../images'
import { memberTaste, sharedCount } from './tabLogic'
import ps from './people.module.css'
import { Avatar } from '../components/Avatar'
import { Spinner } from '../components/RoomPage'
import { Sheet } from './Sheet'
import sh from './shell.module.css'

/**
 * 4-b 참가자 판 (머리의 얼굴을 누르면). 사람별 곡 수, 친구 초대.
 * 내 줄 → 6-h 내 정보, 방장이면 다른 사람 ⋯ → 6-c 관리, 아래 방 설정 → 6-e.
 * I-26 PC 참가자 탭(4-PC″, onFocus가 있을 때): 사람 줄을 누르면 지도에 그 사람 노래만 밝게(다시 누르면 해제).
 * 이때 내 정보는 내 줄 오른쪽의 따로 된 버튼으로 연다.
 */
export function PeopleSheet({ roomId, capacity, version, isHost, onClose, onInvite, onOpenMe, onManage, onSettings, onError, focusId = null, onFocus, map = null, meId = -1 }: {
  roomId: string
  capacity: number
  version: number
  isHost: boolean
  onClose: () => void
  onInvite: () => void
  onOpenMe: () => void
  onManage: (m: MemberRow) => void
  onSettings: () => void
  onError: (err: unknown) => boolean
  /** I-26 PC: 지도에서 밝게 보는 사람 */
  focusId?: number | null
  onFocus?: (memberId: number) => void
  /** 취향 한 줄·나와 같이 n곡 계산용 지도 */
  map?: RoomMap | null
  meId?: number
}) {
  const [rows, setRows] = useState<MemberRow[] | null>(null)

  useEffect(() => {
    const ac = new AbortController()
    roomApi.members(roomId, ac.signal)
      .then(setRows)
      .catch(err => { if (!ac.signal.aborted && !onError(err)) setRows([]) })
    return () => ac.abort()
  }, [roomId, version, onError])

  const count = rows?.length ?? 0
  const empty = Math.max(0, capacity - count)
  const more = (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg>
  )
  return (
    <Sheet title={<>함께하는 사람 <span style={{ color: 'var(--r-accent-strong)' }}>{count}</span> / {capacity}</>} onClose={onClose}>
      {onFocus && (
        <p className={ps.hint}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="3.2" /><ellipse cx="12" cy="12" rx="9" ry="4.2" transform="rotate(-20 12 12)" /></svg>
          누르면 그 사람 노래만 지도에 밝게
        </p>
      )}
      {rows === null && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
      {rows && (
        <ul className={ps.people}>
          {rows.map(m => {
            const c = memberColor(m.colorIdx).bg
            const taste = memberTaste(map, m.id)
            const together = !m.me ? sharedCount(map, meId, m.id) : 0
            const on = focusId === m.id
            const body = (
              <>
                <span className={ps.ring} style={{ boxShadow: `0 0 0 2px ${c}, 0 0 14px color-mix(in srgb, ${c} 45%, transparent)` }}>
                  <Avatar nickname={m.nickname} colorIdx={m.colorIdx} characterId={m.avatarCharacterId} size={44} />
                </span>
                <span className={ps.text}>
                  <span className={ps.nameLine}>
                    <strong>{m.nickname}</strong>
                    {(m.role === 'HOST' || m.me) && <span className={ps.role}>{[m.role === 'HOST' ? '방장' : null, m.me ? '나' : null].filter(Boolean).join(' · ')}</span>}
                  </span>
                  <span className={ps.sub}>
                    노래 {m.songCount}곡{together > 0 && <span className={ps.together}> · 나와 같이 {together}곡</span>}
                  </span>
                  {(taste.galaxy || taste.artists.length > 0) && (
                    <span className={ps.taste} aria-label={`취향: ${[taste.galaxy?.name, ...taste.artists.map(a => a.name)].filter(Boolean).join(', ')}`}>
                      {taste.galaxy && <span className={ps.genre}><i style={{ background: genreColor(taste.galaxy.name, taste.galaxy.id) }} aria-hidden="true" />{taste.galaxy.name}</span>}
                      <span className={ps.faces} aria-hidden="true">
                        {taste.artists.map(a => <TasteFace key={a.artistId} roomId={roomId} name={a.name} face={a.face} />)}
                      </span>
                    </span>
                  )}
                </span>
                {on && <span className={ps.focusTag} style={{ background: c }} aria-hidden="true">지도에서 보는 중</span>}
              </>
            )
            // ⋯는 모든 줄 같은 자리: 내 줄은 내 정보, 방장은 다른 사람 관리. 메뉴가 없는 줄은 같은 폭의 빈 칸
            const menu = m.me
              ? <button type="button" className={ps.more} aria-label="내 정보 열기" onClick={onOpenMe}>{more}</button>
              : isHost
                ? <button type="button" className={ps.more} aria-label={`${m.nickname}님 관리: 새 비밀번호, 내보내기`} onClick={() => onManage(m)}>{more}</button>
                : <span className={ps.moreSpace} aria-hidden="true" />
            return (
              <li key={m.id} className={`${ps.card} ${on ? ps.cardOn : ''}`} style={{ ['--person-c' as string]: c }}>
                {onFocus
                  ? <button type="button" className={ps.main} aria-pressed={on} onClick={() => onFocus(m.id)}
                      aria-label={`${m.nickname}${m.me ? '(나)' : ''}, 노래 ${m.songCount}곡${together ? `, 나와 같이 ${together}곡` : ''}. ${on ? '지도에 이 사람 노래만 밝게 보는 중, 누르면 모두 보기' : '누르면 지도에 이 사람 노래만 밝게'}`}>{body}</button>
                  : <div className={ps.main}>{body}</div>}
                {menu}
              </li>
            )
          })}
        </ul>
      )}
      {rows && empty > 0 && (
        <button type="button" className={ps.seats} onClick={onInvite} aria-label={`${empty}자리 남았어요. 초대 링크 복사`}>
          <span className={ps.seatFaces} aria-hidden="true">{Array.from({ length: empty }, (_, i) => <i key={i} />)}</span>
          <span>{empty}자리 남았어요</span>
        </button>
      )}
      {rows && empty > 0 && (
        <button type="button" className={ps.invite} onClick={onInvite}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 15V3" /><path d="M7 8l5-5 5 5" /><path d="M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5" /></svg>
          친구 초대하기
        </button>
      )}
      {rows && empty === 0 && <span className={sh.center}>자리가 다 찼어요 · 한 우주에는 {capacity}명까지 함께해요</span>}
      {isHost && (
        <button type="button" className={sh.rowButton} style={{ alignSelf: 'center', gap: 6, color: 'var(--r-text-2)', fontSize: 14 }} onClick={onSettings}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1" /></svg>
          방 설정 (이름 변경 · 참가 마감 · 방 삭제)
        </button>
      )}
    </Sheet>
  )
}

function TasteFace({ roomId, name, face }: { roomId: string; name: string; face: RoomMap['planets'][number]['face'] }) {
  const fc = planetFace({ songs: [], face })
  const { src, fail } = useImageChain(roomId, fc.kind === 'genre' ? [] : fc.sources)
  return <span className={ps.face}>{src ? <img src={src} alt="" onError={fail} /> : Array.from(name)[0]}</span>
}
