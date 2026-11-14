import p from '../play.module.css'

/** 취향 월드컵 맛보기: 두 레코드판이 마주 보고 가운데 금빛 VS 자물쇠. 장식 그림 */
export function WorldCupArt() {
  const disc = (cx: number, label: string) => (
    <g className={p.discSpin} style={{ transformOrigin: `${cx}px 48px` }}>
      <circle cx={cx} cy="48" r="38" fill="#141225" stroke="#3a3466" />
      {[30, 24, 18].map(rr => <circle key={rr} cx={cx} cy="48" r={rr} fill="none" stroke="#2a2648" />)}
      <circle cx={cx} cy="48" r="11" fill={label} />
      <circle cx={cx} cy="48" r="2.2" fill="#141225" />
    </g>
  )
  return (
    <svg className={p.cupArt} viewBox="0 0 220 96" aria-hidden="true">
      <circle cx="110" cy="48" r="26" fill="#FFE3B8" opacity=".18" />
      {disc(48, '#B9A2FF')}
      {disc(172, '#E3A6B8')}
      <g transform="translate(110 48)">
        <circle r="17" fill="#221e3f" stroke="#FFE3B8" strokeOpacity=".9" />
        <path d="M-6 -1v-4a6 6 0 0 1 12 0v4" fill="none" stroke="#FFE3B8" strokeWidth="2" strokeLinecap="round" />
        <rect x="-8" y="-1" width="16" height="11" rx="3" fill="#FFE3B8" />
      </g>
    </svg>
  )
}
