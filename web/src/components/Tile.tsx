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

/** 사진 비율에 따른 표시: 세로로 긴 사진은 위쪽(얼굴)을 남기고, 가로로 아주 긴 단체 사진은 자르지 않고 전체를 넣는다 */
type Fit = 'cover' | 'top' | 'contain'

export function fitOf(width: number, height: number): Fit {
  if (!width || !height) return 'cover'
  if (width >= height * 2) return 'contain' // 16:9 단체 사진은 가운데를 잘라도 대부분 보여 자른다
  if (height > width * 1.1) return 'top'
  return 'cover'
}

/** 원곡 음반 표지·가수 사진. 불러오는 동안과 이미지가 없을 때는 글자 타일을 보여 주고, 도착하면 위에 겹쳐 표시한다 */
export function Cover({ url, text, size = 40, title }: { url?: string | null; text: string; size?: number; title?: string }) {
  const [state, setState] = useState<{ url: string; ok: boolean; fit?: Fit } | null>(null)
  if (!url || (state?.url === url && !state.ok)) return <Tile text={text} size={size} />
  const loaded = state?.url === url && state.ok
  return (
    <span title={title} style={{ position: 'relative', width: size, height: size, flex: 'none', display: 'inline-block' }}>
      <Tile text={text} size={size} />
      <img src={url} alt="" width={size} height={size} referrerPolicy="no-referrer"
        onLoad={e => setState({ url, ok: true, fit: fitOf(e.currentTarget.naturalWidth, e.currentTarget.naturalHeight) })}
        onError={() => setState({ url, ok: false })}
        style={{
          position: 'absolute', inset: 0, width: size, height: size, borderRadius: 8, opacity: loaded ? 1 : 0,
          objectFit: state?.fit === 'contain' ? 'contain' : 'cover',
          objectPosition: state?.fit === 'top' ? '50% 0%' : '50% 50%',
          background: state?.fit === 'contain' ? '#111' : undefined,
        }} />
    </span>
  )
}
