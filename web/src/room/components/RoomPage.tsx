import { useEffect, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import s from '../room.module.css'
import o from './outside.module.css'

/** <html data-theme="room">을 켠다. 화면을 떠나면 끈다 (청취 분석 화면은 기존 테마) */
export function useRoomTheme() {
  useEffect(() => {
    const el = document.documentElement
    el.dataset.theme = 'room'
    return () => { delete el.dataset.theme }
  }, [])
}

/** 방 화면 한 장: 휴대폰은 전체 화면, PC는 가운데 카드 */
export function RoomPage({ children, label }: { children: ReactNode; label?: string }) {
  useRoomTheme()
  return (
    <div className={`${s.page} ${o.cosmos}`}>
      <div className={o.sky} aria-hidden="true"><div className={o.nebula} /><div className={o.starsFar} /><div className={o.stars} /></div>
      <main className={`${s.column} ${o.glassCol}`} aria-label={label}>{children}</main>
    </div>
  )
}

/** 뒤로 가기. onClick이 없으면 to로 이동 */
export function BackButton({ to, onClick, label = '뒤로' }: { to?: string; onClick?: () => void; label?: string }) {
  const navigate = useNavigate()
  return (
    <button type="button" className={s.back} aria-label={label} onClick={onClick ?? (() => navigate(to ?? '/'))}>
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M15 18l-6-6 6-6" />
      </svg>
    </button>
  )
}

export function NoteIcon({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" />
    </svg>
  )
}

export function Spinner({ size = 18 }: { size?: number }) {
  return (
    <svg className={s.spin} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden="true">
      <path d="M12 3a9 9 0 1 0 9 9" />
    </svg>
  )
}
