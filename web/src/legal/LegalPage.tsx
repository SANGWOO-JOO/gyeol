import { Fragment, useEffect } from 'react'
import { Link } from 'react-router'
import { useRoomTheme } from '../room/components/RoomPage'
import { Wordmark } from '../room/components/Wordmark'
import s from '../room/room.module.css'
import o from '../room/components/outside.module.css'
import l from './legal.module.css'
import { LegalLinks } from './LegalLinks'
import { parse, toc, type Block, type Inline } from './markdown'
import termsMd from '../../../docs/legal/terms.md?raw'
import privacyMd from '../../../docs/legal/privacy.md?raw'

const DOCS = {
  terms: { md: termsMd, other: { to: '/privacy', label: '개인정보 처리방침' } },
  privacy: { md: privacyMd, other: { to: '/terms', label: '이용약관' } },
} as const

function Inl({ parts }: { parts: Inline[] }) {
  return <>{parts.map((p, i) => p.t === 'b' ? <strong key={i}>{p.v}</strong>
    : p.t === 'a' ? (/^https?:/.test(p.href) ? <a key={i} href={p.href} target="_blank" rel="noopener noreferrer">{p.v}</a> : <a key={i} href={p.href}>{p.v}</a>)
    : <Fragment key={i}>{p.v}</Fragment>)}</>
}

function BlockView({ b }: { b: Block }) {
  switch (b.k) {
    case 'h': return b.level === 1 ? null : b.level === 2 ? <h2 id={b.id} className={l.h2}>{b.text}</h2> : <h3 id={b.id} className={l.h3}>{b.text}</h3>
    case 'p': return <p><Inl parts={b.inl} /></p>
    case 'ul': return <ul>{b.items.map((it, i) => <li key={i}><Inl parts={it} /></li>)}</ul>
    case 'ol': return <ol>{b.items.map((it, i) => <li key={i}><Inl parts={it} /></li>)}</ol>
    case 'note': return <p className={l.note}><Inl parts={b.inl} /></p>
    case 'table': return (
      <div className={l.tableWrap} tabIndex={0} role="region" aria-label="표">
        <table>
          <thead><tr>{b.head.map((c, i) => <th key={i} scope="col"><Inl parts={c} /></th>)}</tr></thead>
          <tbody>{b.rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j}><Inl parts={c} /></td>)}</tr>)}</tbody>
        </table>
      </div>
    )
  }
}

/** /terms, /privacy: docs/legal/*.md 문안을 방 밖 화면 톤(우주 배경 + 유리 판)으로 보여 준다 */
export function LegalPage({ doc }: { doc: keyof typeof DOCS }) {
  useRoomTheme()
  const { md, other } = DOCS[doc]
  const blocks = parse(md)
  const title = blocks.find(b => b.k === 'h' && b.level === 1) as Extract<Block, { k: 'h' }> | undefined
  const items = toc(blocks)
  useEffect(() => { document.title = `${title?.text ?? ''} · gyeol` }, [title?.text])
  return (
    <div className={`${s.page} ${o.cosmos} ${l.page}`}>
      <div className={o.sky} aria-hidden="true"><div className={o.nebula} /><div className={o.starsFar} /><div className={o.stars} /></div>
      <div className={l.wrap}>
        <header className={l.top}>
          <Link to="/" aria-label="첫 화면"><Wordmark size={22} /></Link>
          <Link to={other.to} className={l.switch}>{other.label}</Link>
        </header>
        <h1 className={l.title}>{title?.text}</h1>
        <div className={l.grid}>
          <nav className={l.toc} aria-label="목차">
            <details open className={l.tocBox}>
              <summary>목차</summary>
              <ol>{items.map(h => <li key={h.id}><a href={`#${h.id}`}>{h.text}</a></li>)}</ol>
            </details>
          </nav>
          <main className={l.body}>
            {blocks.map((b, i) => <BlockView key={i} b={b} />)}
          </main>
        </div>
        <LegalLinks />
      </div>
    </div>
  )
}
