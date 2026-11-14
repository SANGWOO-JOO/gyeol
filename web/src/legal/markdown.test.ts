import { describe, expect, it } from 'vitest'
import { inline, parse, safeHref, slug, toc } from './markdown'
import terms from '../../../docs/legal/terms.md?raw'
import privacy from '../../../docs/legal/privacy.md?raw'

describe('markdown', () => {
  it('굵게와 링크를 나눈다', () => {
    expect(inline('a **b** [c](https://x.y) d')).toEqual([
      { t: 'text', v: 'a ' }, { t: 'b', v: 'b' }, { t: 'text', v: ' ' }, { t: 'a', v: 'c', href: 'https://x.y' }, { t: 'text', v: ' d' },
    ])
  })
  it('위험한 링크는 막는다', () => {
    expect(safeHref('javascript:alert(1)')).toBe('#')
    expect(safeHref('/privacy')).toBe('/privacy')
    expect(safeHref('mailto:a@b.c')).toBe('mailto:a@b.c')
  })
  it('조·번호 제목은 고정 id', () => {
    expect(slug('제3조 (정의)', 0)).toBe('a3')
    expect(slug('4. 보관 기간', 0)).toBe('s4')
    expect(slug('부칙', 7)).toBe('h7')
  })
  it('목록·표·인용·주석을 읽는다', () => {
    const b = parse('# T\n\n<!-- 숨김\n여러 줄 -->\n문단\n이어짐\n\n- 가\n- 나\n\n1. 하나\n2. 둘\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n> 주의\n> 계속')
    expect(b.map(x => x.k)).toEqual(['h', 'p', 'ul', 'ol', 'table', 'note'])
    expect(b[1]).toEqual({ k: 'p', inl: [{ t: 'text', v: '문단 이어짐' }] })
    const t = b[4] as Extract<typeof b[number], { k: 'table' }>
    expect(t.rows).toHaveLength(1)
  })
})

describe('문안', () => {
  for (const [name, md] of [['terms', terms], ['privacy', privacy]] as const) {
    it(`${name}: 목차가 있고 id가 겹치지 않는다`, () => {
      const b = parse(md)
      const ids = b.filter(x => x.k === 'h').map(x => (x as { id: string }).id)
      expect(new Set(ids).size).toBe(ids.length)
      expect(toc(b).length).toBeGreaterThan(4)
    })
    it(`${name}: 자리표시자가 남아 있다 (실제 개인정보를 넣지 않음)`, () => {
      expect(md).toContain('{시행일}')
      expect(md).not.toMatch(/[\w.+-]+@(?!example)[\w-]+\.[a-z]{2,}/i)
    })
  }
  it('처리방침에 YouTube 정책 링크가 있다', () => {
    expect(privacy).toContain('https://www.youtube.com/t/terms')
    expect(privacy).toContain('https://policies.google.com/privacy')
    expect(privacy).toContain('https://security.google.com/settings/security/permissions')
  })
})
