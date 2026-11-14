// 결 전용 아이콘 (11-14): 탭 막대·탭바·지도 버튼에 쓰는 인라인 SVG. 장식이라 aria-hidden, 이름은 버튼의 aria-label이 맡는다
import type { ReactNode } from 'react'

const S = ({ children, size = 22 }: { children: ReactNode; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>
)

/** 놀이터: 겹친 두 행성 */
export const PlayIcon = ({ size }: { size?: number }) => <S size={size}><circle cx="9" cy="12" r="5.5" /><circle cx="15" cy="12" r="5.5" /><path d="M12 7.6a5.5 5.5 0 0 1 0 8.8" fill="currentColor" fillOpacity=".35" stroke="none" /></S>
/** 검색: 망원경 */
export const ScopeIcon = ({ size }: { size?: number }) => <S size={size}><path d="M3.5 13.5l12-6.5 2 3.7-12 6.5z" /><path d="M15.5 7l1.6-.9 2 3.7-1.6.9" /><path d="M10 15.5l-2.5 5M10 15.5l2.5 5" /><circle cx="20.5" cy="4" r=".6" fill="currentColor" /></S>
/** 내 노래: 내 색 레코드판 */
export const RecordIcon = ({ size, color }: { size?: number; color?: string }) => <S size={size}><circle cx="12" cy="12" r="8.5" /><circle cx="12" cy="12" r="5.5" strokeOpacity=".45" /><circle cx="12" cy="12" r="2.6" fill={color ?? 'currentColor'} stroke="none" /></S>
/** 참가자: 궤도 도는 점들 */
export const PeopleIcon = ({ size }: { size?: number }) => <S size={size}><ellipse cx="12" cy="12" rx="9" ry="4.5" transform="rotate(-20 12 12)" /><circle cx="12" cy="12" r="2.4" /><circle cx="4.6" cy="14.6" r="1.4" fill="currentColor" /><circle cx="19.4" cy="9.4" r="1.4" fill="currentColor" /><circle cx="15.5" cy="15.8" r="1.1" fill="currentColor" /></S>
/** 가운데로: 궤도 */
export const OrbitIcon = ({ size }: { size?: number }) => <S size={size}><circle cx="12" cy="12" r="2.6" /><ellipse cx="12" cy="12" rx="9" ry="4.2" transform="rotate(-20 12 12)" /></S>

/** 방 이름 앞 작은 행성: 방 사람 색들로 나눈 고리 */
export function RoomPlanet({ colors, size = 26 }: { colors: string[]; size?: number }) {
  const cs = colors.length ? colors : ['#b9a2ff']
  const C = 2 * Math.PI * 10
  const seg = C / cs.length
  return (
    <svg width={size} height={size} viewBox="0 0 26 26" aria-hidden="true" style={{ flex: 'none' }}>
      <defs><radialGradient id="room-planet-g" cx="35%" cy="30%" r="75%"><stop offset="0%" stopColor="#6d5bd0" /><stop offset="100%" stopColor="#241f47" /></radialGradient></defs>
      {cs.map((c, i) => <circle key={i} cx="13" cy="13" r="10" fill="none" stroke={c} strokeWidth="2" strokeDasharray={`${Math.max(.5, seg - (cs.length > 1 ? 1.6 : 0))} ${C}`} strokeDashoffset={-seg * i} transform="rotate(-90 13 13)" strokeLinecap="round" />)}
      <circle cx="13" cy="13" r="6.2" fill="url(#room-planet-g)" />
    </svg>
  )
}
