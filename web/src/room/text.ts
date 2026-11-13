// 화면 문구 도우미 (한국어 조사, 남은 시간)

/** 마지막 글자에 받침이 있으면 true. 한글이 아니면(영문·숫자) 받침 없음으로 본다 */
export function hasBatchim(word: string): boolean {
  const ch = word.trim().slice(-1)
  if (!ch) return false
  const code = ch.charCodeAt(0) - 0xac00
  if (code < 0 || code > 11171) return false
  return code % 28 !== 0
}

/** "나는 민지예요" / "나는 상우예요" / "나는 지훈이에요" */
export function iAm(nickname: string): string {
  return `나는 ${nickname}${hasBatchim(nickname) ? '이에요' : '예요'}`
}

/** 남은 초 → "9:05" */
export function mmss(totalSeconds: number): string {
  const s = Math.max(0, Math.ceil(totalSeconds))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** 남은 초 → "10분" / "1분 미만" (잠금 안내) */
export function minutesText(totalSeconds: number): string {
  const m = Math.ceil(Math.max(0, totalSeconds) / 60)
  return m <= 1 ? '1분' : `${m}분`
}

/** 우주 이름을 비워 두면 닉네임으로 짓는다 (캔버스 2번 화면 안내 문구) */
export function defaultRoomName(nickname: string): string {
  return `${nickname.trim()}의 우주`
}
