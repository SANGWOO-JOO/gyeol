import { useState } from 'react'

// 표지가 없을 때 쓰는 글자 타일 (가수·곡 이름 첫 글자)
const HUES = [160, 200, 250, 290, 20, 40]

function hueOf(s: string) {
  let h = 0
  for (const ch of s) h = (h * 31 + ch.codePointAt(0)!) >>> 0
  return HUES[h % HUES.length]
}

export function Tile({ text, size = 40 }: { text: string; size?: number }) {
  const hue = hueOf(text)
  return (
    <span aria-hidden="true" style={{
      width: size, height: size, flex: 'none', borderRadius: 8, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      background: `hsl(${hue} 45% 50% / .18)`, color: `hsl(${hue} 45% 40%)`, fontWeight: 600, fontSize: size * 0.4,
    }}>
      {[...text.trim()][0] ?? '♪'}
    </span>
  )
}

/** 원곡 음반 표지. 없거나 불러오지 못하면 글자 타일 (이미지는 Cover Art Archive에서 브라우저가 직접 불러옴) */
export function Cover({ url, text, size = 40 }: { url?: string | null; text: string; size?: number }) {
  const [failed, setFailed] = useState(false)
  if (!url || failed) return <Tile text={text} size={size} />
  return (
    <img src={url} alt="" width={size} height={size} loading="lazy" onError={() => setFailed(true)}
      style={{ width: size, height: size, flex: 'none', borderRadius: 8, objectFit: 'cover', background: 'var(--surface-2)' }} />
  )
}
