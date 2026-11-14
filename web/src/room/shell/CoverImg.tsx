import { coverSources, useImageChain } from '../images'

/** I-14 곡 표지 <img>: 저장본(coverPath, 방 쿠키 → 열쇠) → 외부 coverUrl → 빈 칸. 목록·알림에 같이 쓴다 */
export function CoverImg({ roomId, cover, className }: { roomId: string; cover: { coverPath?: string | null; coverUrl?: string | null }; className: string }) {
  const { src, fail } = useImageChain(roomId, coverSources(cover))
  return src ? <img src={src} alt="" className={className} loading="lazy" onError={fail} /> : <span className={className} aria-hidden="true" />
}
