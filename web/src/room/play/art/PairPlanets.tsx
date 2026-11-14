import { useId } from 'react'
import { lensGap } from '../playArt'
import p from '../play.module.css'

export interface PlanetSide { name: string; color: string }

/**
 * 놀이터 첫 장면·결과 그림: 두 사람 행성이 겹치고, 겹친 렌즈가 금빛으로 빛난다(결의 "같이 담은 노래" 금빛). 11-14 A안(글로우)으로 확정.
 * 고르기 전엔 빈 행성 둘 + "?%", 고르면 각자 색과 이름, 결과면 점수만큼 겹친다. 장식 그림이라 aria-hidden
 */
export function PairPlanets({ a, b, score = null, label }: { a?: PlanetSide | null; b?: PlanetSide | null; score?: number | null; label?: string }) {
  const id = useId().replace(/:/g, '')
  const r = 54
  const gap = lensGap(r, score)
  const ax = 160 - gap / 2, bx = 160 + gap / 2, cy = 82
  const ca = a?.color ?? '#3a3466', cb = b?.color ?? '#4a4378'
  const center = score == null ? '?%' : `${score}%`
  return (
    <svg className={p.pair} viewBox="0 0 320 176" role="presentation" aria-hidden="true">
      <defs>
        <radialGradient id={`ga${id}`} cx="35%" cy="30%" r="80%"><stop offset="0%" stopColor={ca} stopOpacity=".95" /><stop offset="100%" stopColor={ca} stopOpacity=".25" /></radialGradient>
        <radialGradient id={`gb${id}`} cx="65%" cy="30%" r="80%"><stop offset="0%" stopColor={cb} stopOpacity=".95" /><stop offset="100%" stopColor={cb} stopOpacity=".25" /></radialGradient>
        <clipPath id={`ca${id}`}><circle cx={ax} cy={cy} r={r} /></clipPath>
        <filter id={`bl${id}`} x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="10" /></filter>
      </defs>
      {/* 궤도: 두 행성을 함께 감싸는 타원, A안에서만 천천히 돈다 */}
      <g className={p.orbitSpin} style={{ transformOrigin: `160px ${cy}px` }}>
        <ellipse cx="160" cy={cy} rx="150" ry="40" fill="none" stroke="#5e5794" strokeOpacity=".55" strokeWidth="1" transform={`rotate(-14 160 ${cy})`} />
        <circle cx="304" cy={cy - 30} r="3.2" fill="#FFE3B8"  />
        <circle cx="22" cy={cy + 34} r="2.4" fill={cb} />
      </g>
      <circle cx={ax} cy={cy} r={r} fill={ca} opacity=".35" filter={`url(#bl${id})`} /><circle cx={bx} cy={cy} r={r} fill={cb} opacity=".35" filter={`url(#bl${id})`} />
      <circle cx={ax} cy={cy} r={r} fill={`url(#ga${id})`} stroke={ca} strokeOpacity={a ? .9 : .5} strokeWidth="1.4" />
      <circle cx={bx} cy={cy} r={r} fill={`url(#gb${id})`} stroke={cb} strokeOpacity={b ? .9 : .5} strokeWidth="1.4" />
      {/* 겹친 렌즈 = 금빛 */}
      <circle cx={bx} cy={cy} r={r} clipPath={`url(#ca${id})`} fill="#FFE3B8" opacity=".9" className={score == null ? p.lensPulse : undefined} />
      <text x="160" y={cy + 7} textAnchor="middle" className={p.lensText}>{center}</text>
      <text x={ax - 18} y={cy + r + 26} textAnchor="middle" className={p.planetName} opacity={a ? 1 : .55}>{a?.name ?? '한 명'}</text>
      <text x={bx + 18} y={cy + r + 26} textAnchor="middle" className={p.planetName} opacity={b ? 1 : .55}>{b?.name ?? '또 한 명'}</text>
      {label && <title>{label}</title>}
    </svg>
  )
}
