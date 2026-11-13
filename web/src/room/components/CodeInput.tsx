import { useId } from 'react'
import s from '../room.module.css'

/**
 * 숫자 칸 입력 (암호 4자리·인증 코드 6자리).
 * 진짜 <input> 하나를 칸 위에 투명하게 겹쳐서 붙여넣기·자동완성(one-time-code)·화면 읽기 프로그램이 그대로 동작한다.
 */
export function CodeInput({
  length, value, onChange, label, id, mask = false, invalid = false, autoFocus = false, onComplete,
}: {
  length: number
  value: string
  onChange: (v: string) => void
  label: string
  id?: string
  mask?: boolean
  invalid?: boolean
  autoFocus?: boolean
  onComplete?: (v: string) => void
}) {
  const auto = useId()
  const inputId = id ?? auto
  return (
    <div className={`${s.code} ${invalid ? s.codeInvalid : ''}`}>
      <input
        id={inputId}
        className={s.codeInput}
        type="text"
        inputMode="numeric"
        pattern="[0-9]*"
        autoComplete={length === 6 && !mask ? 'one-time-code' : 'off'}
        maxLength={length}
        value={value}
        aria-label={label}
        aria-invalid={invalid || undefined}
        autoFocus={autoFocus}
        onChange={e => {
          const v = e.target.value.replace(/\D/g, '').slice(0, length)
          onChange(v)
          if (v.length === length) onComplete?.(v)
        }}
      />
      <div className={s.codeBoxes} aria-hidden="true" style={{ gridTemplateColumns: `repeat(${length}, minmax(0, 1fr))` }}>
        {Array.from({ length }, (_, i) => (
          <span key={i} className={`${s.codeBox} ${i === Math.min(value.length, length - 1) ? s.codeBoxActive : ''}`}>
            {value[i] ? (mask ? '●' : value[i]) : ''}
          </span>
        ))}
      </div>
    </div>
  )
}
