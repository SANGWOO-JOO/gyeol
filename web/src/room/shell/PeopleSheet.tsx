import { useEffect, useState } from 'react'
import { memberColor, roomApi, type MemberRow } from '../roomApi'
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
export function PeopleSheet({ roomId, capacity, version, isHost, onClose, onInvite, onOpenMe, onManage, onSettings, onError, focusId = null, onFocus }: {
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
          {rows.map(m => {
            const body = (
              <>
                <Avatar nickname={m.nickname} colorIdx={m.colorIdx} characterId={m.avatarCharacterId} size={40} />
                <span className={sh.personText}>
                  <span className={sh.personName}>
                    {m.nickname}
                    {(m.role === 'HOST' || m.me) && <span className={sh.roleTag}>{[m.role === 'HOST' ? '방장' : null, m.me ? '나' : null].filter(Boolean).join(' · ')}</span>}
                  </span>
                  <span className={sh.songSub}>노래 {m.songCount}곡</span>
                </span>
              </>
            )
            const chevron = <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#8F87B0" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ flex: 'none' }}><path d="M9 6l6 6-6 6" /></svg>
            if (onFocus) {
              const on = focusId === m.id
              return (
                <li key={m.id} className={`${sh.person} ${on ? sh.personFocused : ''}`} style={{ paddingRight: m.me || isHost ? 4 : 12, ['--person-c' as string]: memberColor(m.colorIdx).bg }}>
                  <button type="button" className={sh.personButton} aria-pressed={on} onClick={() => onFocus(m.id)}
                    aria-label={`${m.nickname}${m.me ? '(나)' : ''}, 노래 ${m.songCount}곡. ${on ? '지도에 이 사람 노래만 밝게 보는 중, 누르면 모두 보기' : '누르면 지도에 이 사람 노래만 밝게'}`}>
                    {body}
                    {on && <span className={sh.focusTag} aria-hidden="true">지도에서 보는 중</span>}
                  </button>
                  {m.me && <button type="button" className={sh.more} aria-label="내 정보 열기" onClick={onOpenMe}>{chevron}</button>}
                  {isHost && !m.me && (
                    <button type="button" className={sh.more} aria-label={`${m.nickname}님 관리: 새 암호, 내보내기`} onClick={() => onManage(m)}>
                      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg>
                    </button>
                  )}
                </li>
              )
            }
            return (
              <li key={m.id} className={sh.person} style={{ paddingRight: m.me || isHost ? 4 : 12 }}>
                {m.me ? (
                  <button type="button" className={sh.personButton} onClick={onOpenMe} aria-label="내 정보 열기">
                    {body}
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#8F87B0" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ flex: 'none', marginRight: 12 }}><path d="M9 6l6 6-6 6" /></svg>
                  </button>
                ) : body}
                {isHost && !m.me && (
                  <button type="button" className={sh.more} aria-label={`${m.nickname}님 관리: 새 암호, 내보내기`} onClick={() => onManage(m)}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg>
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {rows && onFocus && <p className={sh.focusHint}>사람을 누르면 지도에 그 사람 노래만 밝게 보여요 · 다시 누르거나 지도 빈 곳을 누르면 모두 보여요</p>}
      {rows && count < capacity && (
        <button type="button" className={sh.inviteBox} onClick={onInvite}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 15V3" /><path d="M7 8l5-5 5 5" /><path d="M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5" /></svg>
          친구 초대하기
        </button>
      )}
      {rows && count >= capacity && <span className={sh.center}>자리가 다 찼어요 · 한 우주에는 {capacity}명까지 함께해요</span>}
      {isHost && (
        <button type="button" className={sh.rowButton} style={{ alignSelf: 'center', gap: 6, color: 'var(--r-text-2)', fontSize: 14 }} onClick={onSettings}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1" /></svg>
          방 설정 (이름 변경 · 참가 마감 · 방 삭제)
        </button>
      )}
    </Sheet>
  )
}
