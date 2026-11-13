import { useEffect, useState } from 'react'
import { roomApi, type MemberRow } from '../roomApi'
import { Avatar } from '../components/Avatar'
import { Spinner } from '../components/RoomPage'
import { Sheet } from './Sheet'
import sh from './shell.module.css'

/**
 * 4-b 참가자 판 (머리의 얼굴을 누르면). 사람별 곡 수, 친구 초대.
 * 방장의 ⋯(새 암호·내보내기 6-c)·방 설정(6-e)·내 정보(6-h)는 5단계에서 연결한다.
 */
export function PeopleSheet({ roomId, capacity, version, onClose, onInvite, onError }: {
  roomId: string
  capacity: number
  version: number
  onClose: () => void
  onInvite: () => void
  onError: (err: unknown) => boolean
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
  return (
    <Sheet title={<>함께하는 사람 <span style={{ color: 'var(--r-accent-strong)' }}>{count}</span> / {capacity}</>} onClose={onClose}>
      {rows === null && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
      {rows && (
        <ul className={sh.list}>
          {rows.map(m => (
            <li key={m.id} className={sh.person}>
              <Avatar nickname={m.nickname} colorIdx={m.colorIdx} size={40} />
              <span className={sh.personText}>
                <span className={sh.personName}>
                  {m.nickname}
                  {(m.role === 'HOST' || m.me) && <span className={sh.roleTag}>{[m.role === 'HOST' ? '방장' : null, m.me ? '나' : null].filter(Boolean).join(' · ')}</span>}
                </span>
                <span className={sh.songSub}>노래 {m.songCount}곡</span>
              </span>
            </li>
          ))}
        </ul>
      )}
      {rows && count < capacity && (
        <button type="button" className={sh.inviteBox} onClick={onInvite}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 15V3" /><path d="M7 8l5-5 5 5" /><path d="M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5" /></svg>
          친구 초대하기
        </button>
      )}
      {rows && count >= capacity && <span className={sh.center}>자리가 다 찼어요 · 한 우주에는 {capacity}명까지 함께해요</span>}
    </Sheet>
  )
}
