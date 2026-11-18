// 분위기로 가수 찾기 (docs/todo/vibe-search-rag.md 4-2·9절) 화면 규칙
import { RoomApiError } from './roomApi'

export const VIBE_MIN = 2
export const VIBE_MAX = 100
/** 1초 간격 확인, 이만큼 넘게 PENDING이면 멈추고 다시 시도를 띄운다 */
export const VIBE_POLL_MS = 1000
export const VIBE_POLL_MAX = 60

/** 앞뒤 공백을 뺀 글자 수 (서버 2~100자와 같은 기준이라고 보고, 한글 한 글자 = 1) */
export function vibeLength(q: string): number {
  return Array.from(q.trim()).length
}

export function vibeQueryOk(q: string): boolean {
  const n = vibeLength(q)
  return n >= VIBE_MIN && n <= VIBE_MAX
}

/** POST·GET 오류 → 보여 줄 문구 */
export function vibeErrorText(err: unknown): string {
  if (err instanceof RoomApiError) {
    if (err.status === 400 || err.code === 'QUERY_LENGTH') return `${VIBE_MIN}~${VIBE_MAX}자로 적어 주세요`
    if (err.status === 429 || err.code === 'DAILY_LIMIT') return '오늘은 여기까지 찾을 수 있어요. 내일 다시 찾아봐요'
    if (err.status === 0) return err.message
  }
  return '가수를 찾지 못했어요. 잠시 뒤 다시 시도해 주세요'
}

/** "방에서 OO가 담음" 표시. 세 명부터는 "OO 외 N명" */
export function inRoomText(names: string[]): string | null {
  if (names.length === 0) return null
  const who = names.length <= 2 ? names.join('·') : `${names[0]} 외 ${names.length - 1}명`
  return `방에서 ${who}${ga(names.length <= 2 ? names[names.length - 1] : who)} 담음`
}

/** 받침이 있으면 "이", 없으면 "가" (한글이 아니면 "가") */
function ga(word: string): string {
  const c = word.charCodeAt(word.length - 1)
  if (c >= 0xac00 && c <= 0xd7a3) return (c - 0xac00) % 28 === 0 ? '가' : '이'
  if (/[0-9]$/.test(word)) return '이'
  return '가'
}
