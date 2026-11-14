import { CoverImg } from './CoverImg'
import b from './allSongs.module.css'

type Cover = { coverPath?: string | null; coverUrl?: string | null }

/** 표지 후보에서 서로 다른 것 최대 n장 (저장본 주소, 없으면 외부 주소로 같은지 본다) */
export function pickCovers<T extends Cover>(items: T[], n = 3): T[] {
  const seen = new Set<string>()
  const out: T[] = []
  for (const c of items) {
    const k = c.coverPath || c.coverUrl
    if (!k || seen.has(k)) continue
    seen.add(k); out.push(c)
    if (out.length >= n) break
  }
  return out
}

/**
 * "{가수} 노래 전체 보기" (11-14, 놀이터 A안 빛 언어): 왼쪽에 앨범 표지 부채, 가운데 굵은 제목 + 작은 줄,
 * 장르색 → 방 보라 그라데이션, 오른쪽 원 안 화살표. hover면 부채가 살짝 펼쳐진다(움직임 줄이기면 그대로)
 */
export function AllSongsButton({ roomId, name, covers, color, sub, ariaLabel, onClick }: {
  roomId: string; name: string; covers: Cover[]; color: string; sub: string; ariaLabel: string; onClick: () => void
}) {
  const fan = pickCovers(covers, 3)
  return (
    <button type="button" className={b.btn} style={{ ['--g' as string]: color }} aria-label={ariaLabel} onClick={onClick}>
      {fan.length > 0 && (
        <span className={b.fan} aria-hidden="true" data-n={fan.length}>
          {fan.map((c, i) => <CoverImg key={i} roomId={roomId} cover={{ coverPath: c.coverPath, coverUrl: c.coverUrl ?? null }} className={b.card} />)}
        </span>
      )}
      <span className={b.text}>
        <strong>{name} 노래 전체 보기</strong>
        <small>{sub}</small>
      </span>
      <span className={b.arrow} aria-hidden="true">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M9 6l6 6-6 6" /></svg>
      </span>
    </button>
  )
}
