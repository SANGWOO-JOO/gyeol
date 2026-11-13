// gyeol 워드마크: Quicksand 글자 + o 자리에 로즈 미니 LP (docs/design/ci/README.md, concept-d2)

export function Wordmark({ size = 26 }: { size?: number }) {
  return (
    <span
      role="img"
      aria-label="gyeol"
      style={{
        display: 'inline-flex', alignItems: 'baseline', fontFamily: 'var(--r-font-mark)', fontWeight: 700,
        fontSize: size, lineHeight: 1, letterSpacing: '0.5px', color: 'var(--r-silver)',
      }}
    >
      gye
      <span
        aria-hidden="true"
        style={{ position: 'relative', display: 'inline-block', width: '0.54em', height: '0.54em', margin: '0 0.035em', borderRadius: '50%', background: 'var(--r-rose)' }}
      >
        <span style={{ position: 'absolute', left: '50%', top: '50%', width: '0.11em', height: '0.11em', margin: '-0.055em 0 0 -0.055em', borderRadius: '50%', background: 'var(--r-bg)' }} />
      </span>
      l
    </span>
  )
}
