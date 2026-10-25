// 라이브러리 없이 그리는 막대 그래프. 시간대·요일·월별 세 곳에서 쓴다
import { useEffect, useRef, useState } from 'react'

interface Props {
  values: number[]
  labels: string[]
  /** 축에 보일 눈금 위치 (labels 인덱스). 없으면 전부 */
  ticks?: number[]
  unit?: string
  height?: number
  title: string
}

export function BarChart({ values, labels, ticks, unit = '회', height = 140, title }: Props) {
  const [hover, setHover] = useState<number | null>(null)
  // 실제 폭으로 그려야 휴대폰에서도 글자 크기가 그대로 유지된다
  const ref = useRef<HTMLDivElement>(null)
  const [w, setW] = useState(640)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setW(Math.max(200, Math.round(e.contentRect.width))))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  const max = Math.max(1, ...values)
  const top = 20, bottom = 22, chartH = height - top - bottom
  const step = w / values.length, bw = Math.max(2, step * 0.7)
  const shown = ticks ?? values.map((_, i) => i)

  return (
    <div ref={ref}>
      <svg viewBox={`0 0 ${w} ${height}`} width="100%" role="img" aria-label={title} onMouseLeave={() => setHover(null)}>
        <title>{title}</title>
        {values.map((v, i) => {
          const h = Math.round((v / max) * chartH)
          const x = i * step + (step - bw) / 2
          return (
            <g key={i} onMouseEnter={() => setHover(i)}>
              <rect x={i * step} y={top} width={step} height={chartH} fill="transparent" />
              <rect x={x} y={top + chartH - h} width={bw} height={h} rx={3}
                fill="var(--accent-bar)" opacity={hover === null || hover === i ? 1 : 0.45} />
            </g>
          )
        })}
        {shown.map(i => (
          // 양 끝 눈금은 잘리지 않게 안쪽으로 붙인다
          <text key={i} x={i === 0 ? i * step : i === values.length - 1 ? w : i * step + step / 2} y={height - 4}
            textAnchor={i === 0 ? 'start' : i === values.length - 1 ? 'end' : 'middle'} fontSize="12" fill="var(--text-3)">{labels[i]}</text>
        ))}
        {hover !== null && (
          <text x={Math.min(w - 60, Math.max(60, hover * step + step / 2))} y={13} textAnchor="middle" fontSize="13" fill="var(--text)">
            {labels[hover]} · {values[hover].toLocaleString()}{unit}
          </text>
        )}
      </svg>
    </div>
  )
}
