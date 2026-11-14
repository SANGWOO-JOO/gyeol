/** 약관·처리방침 문안(docs/legal/*.md)을 화면 블록으로 바꾸는 작은 마크다운 해석기.
 *  외부 라이브러리 없이 쓰는 문법만 지원: # 제목(1~3), 문단, - 목록, 1. 목록, | 표 |, > 인용(주의 상자), **굵게**, [링크](주소) */

export type Inline = { t: 'text'; v: string } | { t: 'b'; v: string } | { t: 'a'; v: string; href: string }
export type Block =
  | { k: 'h'; level: 1 | 2 | 3; id: string; text: string }
  | { k: 'p'; inl: Inline[] }
  | { k: 'ul' | 'ol'; items: Inline[][] }
  | { k: 'table'; head: Inline[][]; rows: Inline[][][] }
  | { k: 'note'; inl: Inline[] }

export function inline(src: string): Inline[] {
  const out: Inline[] = []
  const re = /\*\*(.+?)\*\*|\[([^\]]+)\]\(([^)\s]+)\)/g
  let last = 0
  for (let m = re.exec(src); m; m = re.exec(src)) {
    if (m.index > last) out.push({ t: 'text', v: src.slice(last, m.index) })
    if (m[1] !== undefined) out.push({ t: 'b', v: m[1] })
    else out.push({ t: 'a', v: m[2], href: safeHref(m[3]) })
    last = m.index + m[0].length
  }
  if (last < src.length) out.push({ t: 'text', v: src.slice(last) })
  return out
}

/** http(s)·mailto·사이트 안 경로만 링크로 둔다 (javascript: 등은 #) */
export function safeHref(h: string): string {
  return /^(https?:\/\/|mailto:|\/|#)/i.test(h) ? h : '#'
}

/** 제목 → 목차용 id. 번호가 있으면 번호로 (예: "제3조 …" → a3, "3. …" → s3), 아니면 순번 */
export function slug(text: string, n: number): string {
  const art = /^제(\d+)조/.exec(text); if (art) return `a${art[1]}`
  const num = /^(\d+)\./.exec(text); if (num) return `s${num[1]}`
  return `h${n}`
}

const cells = (line: string) => line.trim().replace(/^\||\|$/g, '').split('|').map(c => inline(c.trim()))

export function parse(md: string): Block[] {
  const lines = md.replace(/\r\n?/g, '\n').split('\n')
  const out: Block[] = []
  let para: string[] = []
  let n = 0
  const flush = () => { if (para.length) { out.push({ k: 'p', inl: inline(para.join(' ')) }); para = [] } }
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (/^<!--/.test(line.trim())) { while (i < lines.length && !lines[i].includes('-->')) i++; continue }
    const h = /^(#{1,3})\s+(.*)$/.exec(line)
    if (h) { flush(); const text = h[2].trim(); out.push({ k: 'h', level: h[1].length as 1 | 2 | 3, id: slug(text, n++), text }); continue }
    if (/^\s*$/.test(line)) { flush(); continue }
    if (/^\s*[-*]\s+/.test(line) || /^\s*\d+\.\s+/.test(line)) {
      flush()
      const ordered = /^\s*\d+\.\s+/.test(line)
      const items: Inline[][] = []
      const pat = ordered ? /^\s*\d+\.\s+/ : /^\s*[-*]\s+/
      while (i < lines.length && pat.test(lines[i])) { items.push(inline(lines[i].replace(pat, ''))); i++ }
      i--
      out.push({ k: ordered ? 'ol' : 'ul', items })
      continue
    }
    if (/^\s*\|/.test(line)) {
      flush()
      const head = cells(line)
      i++ // 구분 줄 |---|
      const rows: Inline[][][] = []
      while (i + 1 < lines.length && /^\s*\|/.test(lines[i + 1])) { i++; rows.push(cells(lines[i])) }
      out.push({ k: 'table', head, rows })
      continue
    }
    if (/^>\s?/.test(line)) {
      flush()
      const buf: string[] = []
      while (i < lines.length && /^>\s?/.test(lines[i])) { buf.push(lines[i].replace(/^>\s?/, '')); i++ }
      i--
      out.push({ k: 'note', inl: inline(buf.join(' ')) })
      continue
    }
    para.push(line.trim())
  }
  flush()
  return out
}

/** 목차: 2단계 제목만 */
export const toc = (blocks: Block[]) => blocks.filter((b): b is Extract<Block, { k: 'h' }> => b.k === 'h' && b.level === 2)
