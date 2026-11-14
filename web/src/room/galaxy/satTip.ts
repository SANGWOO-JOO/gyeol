// 위성 곡 제목 이름표 (11-14): 위성 바로 위에 띄우고 화면 가장자리에서 잘리지 않게 옮긴다. 꼬리는 위성을 계속 가리킨다.

/** 이름표 최대 폭(px). 넘치는 제목은 한 줄 말줄임 */
export const SAT_TIP_MAX = 200
const PAD_X = 12, ICON = 11 + 6, FONT = 13

/** 글자 폭 추정(px): 한글·한자 등은 1em, 라틴은 0.58em + 여백·음표 아이콘 */
export function satTipWidth(title: string): number {
  let w = 0
  for (const ch of title) w += /[\u0000-ÿ]/.test(ch) ? FONT * 0.58 : FONT
  return Math.min(SAT_TIP_MAX, Math.round(w + PAD_X * 2 + ICON))
}

/**
 * 위성 윗변 가운데(anchor)에서 이름표 위치: 가로는 가운데 정렬 후 화면 안(여백 8px)으로 밀고, 위가 모자라면 아래로 뒤집는다.
 * tailX = 이름표 왼쪽에서 꼬리까지(위성 쪽), 이름표 안쪽 14px 범위로 묶는다
 */
export function satTipPosition(anchor: { x: number; y: number }, width: number, view: { width: number; height: number }, height = 30, gap = 10, margin = 8) {
  const left = Math.min(Math.max(anchor.x - width / 2, margin), Math.max(margin, view.width - margin - width))
  const above = anchor.y - gap - height >= margin
  const top = above ? anchor.y - gap - height : anchor.y + gap
  const tailX = Math.min(Math.max(anchor.x - left, 14), width - 14)
  return { left, top, tailX, below: !above }
}
