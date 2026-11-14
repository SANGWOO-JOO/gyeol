import { useEffect, useId, useRef, useState } from 'react'
import type { PhotoCredit } from '../roomApi'
import { sourcesOf, useImageChain } from '../images'
import { photoCreditText } from '../galaxy/faces'
import sh from './shell.module.css'

/**
 * I-44 가수 판·가수 화면 머리 얼굴: 사진(방 열쇠로 받음)이 있으면 사진, 없거나 못 받으면 이름 첫 글자.
 * 사진 출처(CC BY 저작자 표시)는 평소엔 숨기고, 사진이 실제로 뜬 뒤 얼굴 모서리의 ⓘ를 누르면(마우스를 올리면 툴팁) 보인다.
 */
export function ArtistFace({ roomId, name, photoUrl, credit, className }: {
  roomId: string; name: string; photoUrl?: string | null; className: string
  /** Commons 사진 출처. 있으면 ⓘ로 보여 준다 */
  credit?: PhotoCredit | null
}) {
  const { src, fail } = useImageChain(roomId, sourcesOf([photoUrl]))
  // 출처는 그림이 실제로 뜬 뒤(onLoad)에만. 주소가 바뀌거나 실패하면 숨긴다
  // 뜬 그림의 주소로 기억한다 (캐시된 그림은 onLoad가 effect보다 먼저 와서, effect로 지우면 사라진다)
  const [loadedSrc, setLoadedSrc] = useState<string | null>(null)
  const loaded = !!src && loadedSrc === src
  const text = credit ? photoCreditText(credit) : null
  return (
    <span className={sh.faceWrap}>
      <span className={className} aria-hidden="true" style={src ? { padding: 0, overflow: 'hidden' } : undefined}>
        {src ? <img src={src} alt="" className={sh.facePhoto} onLoad={() => setLoadedSrc(src)} onError={() => { setLoadedSrc(null); fail() }} /> : <span>{Array.from(name)[0] ?? ''}</span>}
      </span>
      {src && loaded && text && <CreditButton text={text} pageUrl={credit?.pageUrl} />}
    </span>
  )
}

/** 얼굴 모서리 ⓘ: 누르면 출처 말풍선(원본 파일 페이지 링크), 바깥을 누르면 닫힘 */
export function CreditButton({ text, pageUrl }: { text: string; pageUrl?: string | null }) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLSpanElement>(null)
  const id = useId()
  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('pointerdown', onDown)
    return () => document.removeEventListener('pointerdown', onDown)
  }, [open])
  return (
    <span ref={box} className={sh.creditBox}>
      <button type="button" className={sh.creditButton} title={text} aria-label="사진 출처 보기" aria-expanded={open} aria-controls={id} onClick={() => setOpen(v => !v)}>
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" aria-hidden="true"><path d="M12 11v6" /><circle cx="12" cy="7" r=".6" fill="currentColor" /></svg>
      </button>
      {open && (
        <span id={id} role="note" className={sh.creditPop}>
          {pageUrl ? <a href={pageUrl} target="_blank" rel="noopener noreferrer">{text}</a> : text}
        </span>
      )}
    </span>
  )
}
