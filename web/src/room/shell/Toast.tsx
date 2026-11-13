import { useEffect } from 'react'
import sh from './shell.module.css'

export interface ToastData {
  id: number
  title: string
  sub?: string
  tone?: 'normal' | 'gold' | 'error'
  cover?: string | null
  action?: { label: string; run: () => void }
}

/** 탭 바 바로 위 알림 (4-e 담김·4-h 겹침·오류). 6초 뒤 사라진다 */
export function Toast({ toast, onClose }: { toast: ToastData | null; onClose: () => void }) {
  useEffect(() => {
    if (!toast) return
    const t = setTimeout(onClose, 6000)
    return () => clearTimeout(t)
  }, [toast, onClose])

  if (!toast) return null
  const cls = toast.tone === 'gold' ? sh.toastGold : toast.tone === 'error' ? sh.toastError : ''
  return (
    <div key={toast.id} className={`${sh.toast} ${cls}`} role={toast.tone === 'error' ? 'alert' : 'status'}>
      {toast.cover !== undefined && (toast.cover ? <img src={toast.cover} alt="" className={sh.toastCover} /> : <span className={sh.toastCover} aria-hidden="true" />)}
      <span className={sh.toastText}>
        <strong>{toast.title}</strong>
        {toast.sub && <span>{toast.sub}</span>}
      </span>
      {toast.action && (
        <button type="button" className={sh.toastAction} onClick={() => { toast.action!.run(); onClose() }}>{toast.action.label}</button>
      )}
    </div>
  )
}
