import { coverSources, useImageChain } from '../images'
import sh from './shell.module.css'

/** I-14 곡 표지 <img>: 저장본(coverPath, 방 쿠키 → 열쇠) → 외부 coverUrl → 자리 그림(작은 레코드판, tint = 장르색). 목록·알림에 같이 쓴다 */
export function CoverImg({ roomId, cover, className, tint }: { roomId: string; cover: { coverPath?: string | null; coverUrl?: string | null }; className: string; tint?: string }) {
  const { src, fail } = useImageChain(roomId, coverSources(cover))
  if (src) return <img src={src} alt="" className={className} loading="lazy" onError={fail} />
  return (
    <span className={`${className} ${sh.coverEmpty}`} aria-hidden="true">
      <svg viewBox="0 0 48 48" width="100%" height="100%">
        {/* 가수(또는 장르) 색이 있으면 판 전체를 그 색으로 은은하게 물들인다 (I-72 W-3) */}
        {tint && <circle cx="24" cy="24" r="22" fill={tint} opacity=".16" />}
        <circle cx="24" cy="24" r="17" fill="#141225" stroke={tint ?? 'rgba(255,255,255,.12)'} strokeOpacity={tint ? 0.55 : 1} />
        <circle cx="24" cy="24" r="12.5" fill="none" stroke={tint ?? 'rgba(255,255,255,.07)'} strokeOpacity={tint ? 0.22 : 1} />
        <circle cx="24" cy="24" r="9" fill="none" stroke={tint ?? 'rgba(255,255,255,.07)'} strokeOpacity={tint ? 0.22 : 1} />
        <circle cx="24" cy="24" r="6.5" fill={tint ?? '#8e7bd0'} />
        <circle cx="24" cy="24" r="1.4" fill="#141225" />
      </svg>
    </span>
  )
}
