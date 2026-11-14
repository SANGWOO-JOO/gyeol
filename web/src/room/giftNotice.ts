// I-51 담은 직후 선물 상자 알림: 201(바로 담김)과 202 → 식별 끝(song-requests)이 같은 규칙을 쓴다.
import type { GiftNotice } from './roomApi'

/**
 * 알림으로 띄울 내용. WAITING(오늘 이미 상자를 받아 내일 열림)만 알린다: READY는 내 정보를 다시 받으면 상자 창이 뜬다.
 * 같은 상자(boxId)를 이미 알렸으면 null (식별 결과를 두 번 받아도 한 번만)
 */
export function giftNoticeToast(gift: GiftNotice | null | undefined, shown: ReadonlySet<number>): { title: string; sub: string } | null {
  if (!gift || gift.status !== 'WAITING' || shown.has(gift.boxId)) return null
  return { title: `${gift.milestone}곡 선물 상자가 생겼어요`, sub: '오늘 선물은 이미 받아서 내일 열려요' }
}
