import { createContext, useContext, useEffect, useState } from 'react'
import { characterImage, memberColor } from '../roomApi'
import s from '../room.module.css'

/** 캐릭터 그림을 불러올 방 (열쇠가 있는 방 화면에서만 RoomShell이 넣는다). 없으면 글자 얼굴 */
export const AvatarRoom = createContext<string | null>(null)

/** 캐릭터 그림 blob 주소. 못 불러오면 null */
export function useCharacterImage(roomId: string | null, characterId: number | null | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!roomId || characterId == null) { setUrl(null); return }
    let alive = true
    characterImage(roomId, characterId).then(u => { if (alive) setUrl(u) })
    return () => { alive = false }
  }, [roomId, characterId])
  return url
}

/** 참가자 얼굴 원: 선물 캐릭터를 얼굴로 쓰면 그 그림, 아니면 사람별 색 + 닉네임 첫 글자 */
export function Avatar({ nickname, colorIdx, size = 32, characterId }: { nickname: string; colorIdx: number; size?: number; characterId?: number | null }) {
  const roomId = useContext(AvatarRoom)
  const img = useCharacterImage(roomId, characterId)
  const c = memberColor(colorIdx)
  if (img) {
    return <img src={img} alt="" aria-hidden="true" className={s.avatar} style={{ width: size, height: size, objectFit: 'cover', background: c.bg }} />
  }
  return (
    <span className={s.avatar} aria-hidden="true" style={{ width: size, height: size, background: c.bg, color: c.fg, fontSize: Math.round(size * 0.42) }}>
      {Array.from(nickname.trim())[0] ?? '?'}
    </span>
  )
}
