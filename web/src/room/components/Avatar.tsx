import { memberColor } from '../roomApi'
import s from '../room.module.css'

/** 참가자 얼굴 원: 사람별 색 + 닉네임 첫 글자 (선물 캐릭터 얼굴은 6단계에서) */
export function Avatar({ nickname, colorIdx, size = 32 }: { nickname: string; colorIdx: number; size?: number }) {
  const c = memberColor(colorIdx)
  return (
    <span className={s.avatar} aria-hidden="true" style={{ width: size, height: size, background: c.bg, color: c.fg, fontSize: Math.round(size * 0.42) }}>
      {Array.from(nickname.trim())[0] ?? '?'}
    </span>
  )
}
