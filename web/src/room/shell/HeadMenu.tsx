import { useEffect, useRef, useState } from 'react'
import sh from './shell.module.css'

/** 머리 ⋯ 메뉴 (11-14): 목록으로 보기/지도로 보기 · 방 설정(방장) · 내 정보. 바깥을 누르거나 Esc면 닫힌다 */
export function HeadMenu({ items, className }: { items: { label: string; onSelect: () => void }[]; className: string }) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const btn = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!open) return
    const down = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') { setOpen(false); btn.current?.focus() } }
    document.addEventListener('pointerdown', down)
    document.addEventListener('keydown', key)
    // 열리면 첫 항목으로
    box.current?.querySelector<HTMLButtonElement>('[role=menuitem]')?.focus()
    return () => { document.removeEventListener('pointerdown', down); document.removeEventListener('keydown', key) }
  }, [open])
  return (
    <div ref={box} className={sh.headMenu}>
      <button ref={btn} type="button" className={className} aria-label="메뉴" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(v => !v)}>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg>
      </button>
      {open && (
        <div role="menu" className={sh.headMenuList}
          onKeyDown={e => {
            const list = [...e.currentTarget.querySelectorAll<HTMLButtonElement>('[role=menuitem]')]
            const i = list.indexOf(document.activeElement as HTMLButtonElement)
            if (e.key === 'ArrowDown') { e.preventDefault(); list[(i + 1) % list.length]?.focus() }
            if (e.key === 'ArrowUp') { e.preventDefault(); list[(i - 1 + list.length) % list.length]?.focus() }
          }}>
          {items.map(it => <button key={it.label} type="button" role="menuitem" onClick={() => { setOpen(false); it.onSelect() }}>{it.label}</button>)}
        </div>
      )}
    </div>
  )
}
