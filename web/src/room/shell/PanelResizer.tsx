import { useEffect, useRef, type KeyboardEvent, type PointerEvent } from 'react'
import { dragPanelWidth, keyPanelWidth, panelMax, PANEL_MIN } from './panelPref'
import sh from './shell.module.css'

/**
 * PC 오른쪽 판 왼쪽 경계: 끌어서(마우스·펜·터치) 판 너비를 바꾼다. 더블클릭은 기본 너비로.
 * 키보드는 role=separator(←→ 16px, Home·End). 끄는 동안은 글자 선택·지도 끌기가 일어나지 않게 막고, 너비 반영은 rAF로 묶는다
 */
export function PanelResizer({ width, viewport, controls, onChange, onCommit, onReset, onDragging }: {
  width: number; viewport: number; controls: string
  /** 끄는 중 (저장하지 않음) */
  onChange: (w: number) => void
  /** 끌기를 마침·키보드 (저장) */
  onCommit: (w: number) => void
  onReset: () => void
  onDragging: (v: boolean) => void
}) {
  const drag = useRef<{ id: number; startX: number; startW: number; last: number } | null>(null)
  const frame = useRef(0)
  useEffect(() => () => { cancelAnimationFrame(frame.current); document.body.style.removeProperty('user-select'); document.body.style.removeProperty('cursor') }, [])

  const end = (e: PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    drag.current = null
    cancelAnimationFrame(frame.current)
    document.body.style.removeProperty('user-select'); document.body.style.removeProperty('cursor')
    onDragging(false)
    onCommit(d.last)
  }
  return (
    <div
      role="separator" aria-orientation="vertical" aria-label="오른쪽 판 너비 (좌우 화살표로 조절, 두 번 누르면 기본 너비)" aria-controls={controls}
      aria-valuenow={width} aria-valuemin={PANEL_MIN} aria-valuemax={panelMax(viewport)} tabIndex={0}
      className={sh.panelResizer}
      onPointerDown={e => {
        if (e.button !== 0) return
        e.preventDefault(); e.stopPropagation()
        e.currentTarget.setPointerCapture(e.pointerId)
        drag.current = { id: e.pointerId, startX: e.clientX, startW: width, last: width }
        document.body.style.userSelect = 'none'; document.body.style.cursor = 'col-resize'
        onDragging(true)
      }}
      onPointerMove={e => {
        const d = drag.current
        if (!d || d.id !== e.pointerId) return
        d.last = dragPanelWidth(d.startW, d.startX, e.clientX, viewport)
        cancelAnimationFrame(frame.current)
        frame.current = requestAnimationFrame(() => onChange(d.last))
      }}
      onPointerUp={end}
      onPointerCancel={end}
      onLostPointerCapture={end}
      onDoubleClick={onReset}
      onKeyDown={(e: KeyboardEvent<HTMLDivElement>) => {
        const w = keyPanelWidth(width, e.key, viewport)
        if (w == null) return
        e.preventDefault()
        onCommit(w)
      }}
    />
  )
}
