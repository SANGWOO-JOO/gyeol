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

/** 원곡 음반 표지. 불러오는 동안과 표지가 없을 때는 글자 타일을 보여 주고, 도착하면 위에 겹쳐 표시한다 */
export function Cover({ url, text, size = 40 }: { url?: string | null; text: string; size?: number }) {
  const [state, setState] = useState<{ url: string; ok: boolean } | null>(null)
  if (!url || (state?.url === url && !state.ok)) return <Tile text={text} size={size} />
  const loaded = state?.url === url && state.ok
  return (
    <span style={{ position: 'relative', width: size, height: size, flex: 'none', display: 'inline-block' }}>
      <Tile text={text} size={size} />
      <img src={url} alt="" width={size} height={size} onLoad={() => setState({ url, ok: true })} onError={() => setState({ url, ok: false })}
        style={{ position: 'absolute', inset: 0, width: size, height: size, borderRadius: 8, objectFit: 'cover', opacity: loaded ? 1 : 0 }} />
    </span>
  )
}
