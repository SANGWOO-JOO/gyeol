import { Link } from 'react-router'
import l from './legal.module.css'

/** 화면 맨 아래 작은 약관 링크 (첫 화면·방 밖 화면·방 계정 판에서 씀) */
export function LegalLinks({ className }: { className?: string }) {
  return (
    <nav className={`${l.links} ${className ?? ''}`} aria-label="약관">
      <Link to="/terms">이용약관</Link>
      <Link to="/privacy"><strong>개인정보 처리방침</strong></Link>
    </nav>
  )
}
