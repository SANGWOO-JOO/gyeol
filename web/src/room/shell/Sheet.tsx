import { createContext, useContext, useEffect, useId, useRef, type ReactNode } from 'react'
import sh from './shell.module.css'

/**
 * 판을 어떻게 보여 줄지 (PC 배치, ⑰):
 * - sheet: 아래에서 올라오는 판 (휴대폰, 기본값)
 * - panel: 오른쪽 판 안 (PC의 가수 자세히·내 정보). 닫기 버튼 있음
 * - tab: 오른쪽 판 탭 내용 (PC의 참가자). 닫기 버튼 없음
 * - dialog: 방 위 가운데 대화상자 (PC의 설정·관리·선물)
 */
export type SheetMode = 'sheet' | 'panel' | 'tab' | 'dialog'
export const SheetModeContext = createContext<SheetMode>('sheet')

/**
 * 판 (4-b 참가자·4′ 가수·6-h 내 정보 등). 배경을 누르거나 Esc로 닫는다.
 * 열릴 때 판으로 초점을 옮기고, 닫히면 연 버튼으로 돌려준다.
 */
export function Sheet({ title, onClose, children, head, sub }: { title: ReactNode; onClose: () => void; children: ReactNode; head?: ReactNode; sub?: ReactNode }) {
  const mode = useContext(SheetModeContext)
  const ref = useRef<HTMLElement>(null)
  const titleId = useId()
  const modal = mode === 'sheet' || mode === 'dialog'

  useEffect(() => {
    if (mode === 'tab') return
    const opener = document.activeElement as HTMLElement | null
    ref.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    if (!modal) return () => document.removeEventListener('keydown', onKey)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = prev
      opener?.focus?.()
    }
  }, [onClose, mode, modal])

  const inner = (
    <>
      {mode === 'sheet' && <span className={sh.handle} aria-hidden="true" />}
      <div className={sh.sheetHead}>
        {head}
        <div className={sh.sheetTitleBox}>
          <h2 id={titleId} className={sh.sheetTitle}>{title}</h2>
          {sub}
        </div>
        {mode !== 'tab' && (
          <button type="button" className={sh.close} aria-label="닫기" onClick={onClose}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        )}
      </div>
      <div className={sh.sheetBody}>{children}</div>
    </>
  )

  if (!modal) {
    return (
      <section ref={ref} className={sh.panelSheet} aria-labelledby={titleId} tabIndex={-1}>{inner}</section>
    )
  }
  return (
    <div className={`${sh.sheetLayer} ${mode === 'dialog' ? sh.dialogLayer : ''}`}>
      <div className={sh.backdrop} onClick={onClose} aria-hidden="true" />
      <section ref={ref} className={`${sh.sheet} ${mode === 'dialog' ? sh.dialogBox : ''}`} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        {inner}
      </section>
    </div>
  )
}
