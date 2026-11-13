import { useEffect, useId, useRef, type ReactNode } from 'react'
import sh from './shell.module.css'

/**
 * 아래에서 올라오는 판 (4-b 참가자·4′ 가수). 배경을 누르거나 Esc로 닫는다.
 * 열릴 때 판으로 초점을 옮기고, 닫히면 연 버튼으로 돌려준다.
 */
export function Sheet({ title, onClose, children, head, sub }: { title: ReactNode; onClose: () => void; children: ReactNode; head?: ReactNode; sub?: ReactNode }) {
  const ref = useRef<HTMLElement>(null)
  const titleId = useId()

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    ref.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = prev
      opener?.focus?.()
    }
  }, [onClose])

  return (
    <div className={sh.sheetLayer}>
      <div className={sh.backdrop} onClick={onClose} aria-hidden="true" />
      <section ref={ref} className={sh.sheet} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <span className={sh.handle} aria-hidden="true" />
        <div className={sh.sheetHead}>
          {head}
          <div className={sh.sheetTitleBox}>
            <h2 id={titleId} className={sh.sheetTitle}>{title}</h2>
            {sub}
          </div>
          <button type="button" className={sh.close} aria-label="닫기" onClick={onClose}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
        <div className={sh.sheetBody}>{children}</div>
      </section>
    </div>
  )
}
