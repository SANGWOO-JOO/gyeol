import { useEffect, useState } from 'react'

/** PC 배치 기준 (⑰): 이 너비부터 지도 왼쪽 + 오른쪽 판. 그보다 좁으면 휴대폰 배치 그대로 */
export const WIDE_QUERY = '(min-width: 1024px)'

export function useWide(): boolean {
  const get = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(WIDE_QUERY).matches
  const [wide, setWide] = useState(get)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const mq = window.matchMedia(WIDE_QUERY)
    const on = () => setWide(mq.matches)
    on()
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  return wide
}
