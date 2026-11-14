import { useEffect } from 'react'
import type { PhotoCredit } from '../roomApi'
import { sourcesOf, useImageChain } from '../images'
import { photoCreditText } from '../galaxy/faces'
import sh from './shell.module.css'

/** I-44 가수 판·가수 화면 머리 얼굴: 사진(방 열쇠로 받음)이 있으면 사진, 없거나 못 받으면 이름 첫 글자 */
export function ArtistFace({ roomId, name, photoUrl, className, onShown }: { roomId: string; name: string; photoUrl?: string | null; className: string; /** 사진을 실제로 보였는지 (출처 줄은 보일 때만) */ onShown?: (shown: boolean) => void }) {
  const { src, fail } = useImageChain(roomId, sourcesOf([photoUrl]))
  // 출처 줄은 그림이 실제로 뜬 뒤(onLoad)에만. 주소가 바뀌거나 실패하면 숨긴다
  useEffect(() => { if (!src) onShown?.(false) }, [src]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <span className={className} aria-hidden="true" style={src ? { padding: 0, overflow: 'hidden' } : undefined}>
      {src ? <img src={src} alt="" className={sh.facePhoto} onLoad={() => onShown?.(true)} onError={() => { onShown?.(false); fail() }} /> : Array.from(name)[0] ?? ''}
    </span>
  )
}

/** 4-n4 사진 출처 한 줄: Commons 사진일 때만. 원본 파일 페이지로 링크 */
export function PhotoCreditLine({ photo }: { photo?: PhotoCredit | null }) {
  const text = photo ? photoCreditText(photo) : null
  if (!text) return null
  return photo?.pageUrl
    ? <a className={sh.photoCredit} href={photo.pageUrl} target="_blank" rel="noopener noreferrer">{text}</a>
    : <span className={sh.photoCredit}>{text}</span>
}
