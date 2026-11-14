import { describe, expect, it } from 'vitest'
import { MEMBER_COLORS, SHARED_GOLD } from './roomApi'

// CIE Lab 색 거리(ΔE76)와 Machado 2009 색약 시뮬레이션 (심도 1.0)
const hex = (h: string) => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255)
const lin = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
const gam = (c: number) => { c = Math.min(1, Math.max(0, c)); return c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055 }
const SIM: Record<string, number[][]> = {
  deutan: [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.011820, 0.042940, 0.968881]],
  protan: [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
  tritan: [[1.255528, -0.076749, -0.178779], [-0.078411, 0.930809, 0.147602], [0.004733, 0.691367, 0.303900]],
}
const sim = (rgb: number[], m?: number[][]) => { if (!m) return rgb; const l = rgb.map(lin); return m.map(r => gam(r[0] * l[0] + r[1] * l[1] + r[2] * l[2])) }
const lab = (rgb: number[]) => {
  const [r, g, b] = rgb.map(lin)
  const f = (t: number) => (t > 216 / 24389 ? Math.cbrt(t) : (24389 / 27 * t + 16) / 116)
  const X = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047), Y = f(0.2126 * r + 0.7152 * g + 0.0722 * b), Z = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
  return [116 * Y - 16, 500 * (X - Y), 200 * (Y - Z)]
}
const dE = (a: number[], b: number[]) => { const x = lab(a), y = lab(b); return Math.hypot(x[0] - y[0], x[1] - y[1], x[2] - y[2]) }
const lum = (rgb: number[]) => { const [r, g, b] = rgb.map(lin); return 0.2126 * r + 0.7152 * g + 0.0722 * b }
const contrast = (a: string, b: string) => { const [x, y] = [lum(hex(a)), lum(hex(b))].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05) }

describe('사람 색 팔레트', () => {
  const views: (number[][] | undefined)[] = [undefined, SIM.deutan, SIM.protan, SIM.tritan]
  it('각 색이 "같이" 금빛과 충분히 떨어진다 (정상·색약 모두 ΔE 18 이상: 가장 가까운 짝은 민트·적색맹 19.9)', () => {
    for (const c of MEMBER_COLORS) for (const v of views) expect(dE(sim(hex(c.bg), v), sim(hex(SHARED_GOLD), v))).toBeGreaterThan(18)
  })
  it('다섯 색이 서로 구분된다 (정상 시각 ΔE 20 이상)', () => {
    for (let i = 0; i < MEMBER_COLORS.length; i++) for (let j = i + 1; j < MEMBER_COLORS.length; j++) {
      expect(dE(hex(MEMBER_COLORS[i].bg), hex(MEMBER_COLORS[j].bg))).toBeGreaterThan(20)
    }
  })
  it('짙은 보라 배경 위 대비 4.5 이상, 색 위 글자색 대비 4.5 이상', () => {
    for (const c of MEMBER_COLORS) {
      expect(contrast(c.bg, '#1e1b36')).toBeGreaterThan(4.5)
      expect(contrast(c.bg, c.fg)).toBeGreaterThan(4.5)
    }
  })
})
