// 표지 대신 쓰는 글자 타일. 실제 앨범 표지는 L2 곡 식별 뒤에 넣는다 (docs/l1-design.md 4-3)
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
