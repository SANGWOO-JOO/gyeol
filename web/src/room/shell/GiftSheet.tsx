import { useEffect, useState } from 'react'
import { roomApi, RoomApiError, type CharacterView, type GiftBoxDetail, type GiftBoxView, type MeView } from '../roomApi'
import { Avatar, useCharacterImage } from '../components/Avatar'
import { genreMixText, giftResultText, imageQuota } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import { Sheet } from './Sheet'
import type { ToastData } from './Toast'
import s from '../room.module.css'
import sh from './shell.module.css'

/**
 * 4-j 선물 상자 → 열기 → 캐릭터 만드는 중 → 4-k 새 캐릭터 받기 · 바꿀까요.
 * 열면 worker가 만든다(로컬 가짜 모드는 기본 그림, 1초 안). 2초마다 상자 상태를 본다. 닫아도 계속 만들어진다.
 */
export function GiftSheet({ roomId, boxId, me, onClose, onAvatarChanged, onError, show }: {
  roomId: string; boxId: number; me: MeView; onClose: () => void; onAvatarChanged: () => void; onError: (e: unknown) => boolean
  show: (t: Omit<ToastData, 'id'>) => void
}) {
  const [box, setBox] = useState<GiftBoxView | null>(null)
  const [character, setCharacter] = useState<CharacterView | null>(null)
  const [mix, setMix] = useState<string | null>(null)
  const [detail, setDetail] = useState<GiftBoxDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 상자 상태: 처음 한 번, 만드는 중이면 2초마다
  useEffect(() => {
    let alive = true
    let timer: ReturnType<typeof setTimeout> | undefined
    const load = async () => {
      try {
        const d = await roomApi.giftBox(roomId, boxId)
        if (!alive) return
        setBox(d.box); setCharacter(d.character); setMix(genreMixText(d.genreMix)); setDetail(d)
        if (d.box.status === 'GENERATING') timer = setTimeout(load, 2000)
      } catch (err) {
        if (alive && !onError(err)) setError(err instanceof RoomApiError ? err.message : '상자를 불러오지 못했어요')
      }
    }
    load()
    return () => { alive = false; if (timer) clearTimeout(timer) }
  }, [roomId, boxId, onError, busy])

  async function open() {
    setBusy(true); setError(null)
    try {
      const d = await roomApi.openGift(roomId, boxId)
      setBox(d.box)
    } catch (err) {
      if (!onError(err)) setError(err instanceof RoomApiError ? err.message : '상자를 열지 못했어요')
    } finally {
      setBusy(false)
    }
  }

  async function useCharacter() {
    if (!character) return
    setBusy(true)
    try {
      await roomApi.setAvatar(roomId, character.id)
      show({ title: '새 캐릭터로 바꿨어요', sub: '참가자 판과 머리의 얼굴에 보여요' })
      onAvatarChanged()
      onClose()
    } catch (err) {
      if (!onError(err)) setError(err instanceof RoomApiError ? err.message : '바꾸지 못했어요')
      setBusy(false)
    }
  }

  const status = box?.status
  const milestone = box?.milestone ?? 50
  // ⑯ 변경(11-14): 오늘 남은 새 그림 (상자 상세 → 없으면 내 정보 요약, 둘 다 없으면 표시 생략)
  const quota = imageQuota(detail?.imagesLeftToday ?? me.gift?.imagesLeftToday, detail?.imageDailyLimit ?? me.gift?.imageDailyLimit)
  const fallbackReason = detail?.fallbackReason ?? character?.fallbackReason ?? null
  return (
    <Sheet title={status === 'OPENED' ? '새 캐릭터가 왔어요' : `내 노래 ${milestone}곡 선물`} onClose={onClose}>
      {!box && !error && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
      {error && <p className={s.error} role="alert">{error}</p>}

      {(status === 'READY' || status === 'GENERATING') && (
        <div className={sh.giftStage}>
          <GiftBoxArt />
          <span className={sh.goldTag}>내 노래 {milestone}곡 달성</span>
          {status === 'READY' ? (
            <>
              {quota?.soldOut ? (
                <>
                  <h2 className={sh.giftTitle}>오늘 새 그림은<br />다 나갔어요</h2>
                  <p className={sh.giftText}>기본 캐릭터를 지금 받거나, 내일 새 그림으로 열 수 있어요.<br />내일 열기를 고르면 상자는 그대로 남아요.</p>
                  <span className={sh.quota}>{quota.text}</span>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 10, width: '100%' }}>
                    <button type="button" className={s.primary} style={{ height: 52, fontSize: 16 }} onClick={open} disabled={busy}>{busy ? <Spinner /> : '기본 캐릭터로 지금 받기'}</button>
                    <button type="button" className={s.secondary} onClick={onClose} disabled={busy}>내일 새 그림으로 열기</button>
                  </div>
                </>
              ) : (
                <>
                  <h2 className={sh.giftTitle}>{me.member.nickname}님 취향으로<br />캐릭터를 만들어 드려요</h2>
                  <p className={sh.giftText}>담은 노래의 장르와 분위기로 하나뿐인 캐릭터를 그려요</p>
                  {quota && <span className={sh.quota}>{quota.text}</span>}
                  <button type="button" className={s.primary} style={{ height: 52, fontSize: 16 }} onClick={open} disabled={busy}>{busy ? <Spinner /> : '상자 열기'}</button>
                </>
              )}
            </>
          ) : (
            <>
              <h2 className={sh.giftTitle}>{me.member.nickname}님 취향으로<br />캐릭터를 만들고 있어요</h2>
              <p className={sh.giftText}>담은 노래 {milestone}곡의 장르와 분위기를 읽는 중이에요</p>
              <span role="status" aria-label="만드는 중" className={sh.dots}><span /><span /><span /></span>
              <button type="button" className={s.ghost} onClick={onClose}>닫아도 돼요 · 다 만들면 내 노래 탭에서 볼 수 있어요</button>
            </>
          )}
        </div>
      )}

      {status === 'OPENED' && character && (
        <Pick roomId={roomId} me={me} character={character} fallbackReason={fallbackReason} milestone={milestone} mix={mix} busy={busy} onUse={useCharacter} onKeep={onClose} />
      )}

      {status === 'FAILED' && (
        <div className={sh.giftStage}>
          <h2 className={sh.giftTitle}>캐릭터를 만들지 못했어요</h2>
          <p className={sh.giftText}>잠시 뒤 다시 열어 주세요. 상자는 사라지지 않아요.</p>
        </div>
      )}
      {status === 'WAITING' && (
        <div className={sh.giftStage}>
          <GiftBoxArt />
          <h2 className={sh.giftTitle}>이 상자는 내일 열려요</h2>
          <p className={sh.giftText}>선물 상자는 하루에 하나씩 열 수 있어요.</p>
        </div>
      )}
    </Sheet>
  )
}

/** 4-k 새 캐릭터 받기 · 바꿀까요 */
function Pick({ roomId, me, character, fallbackReason, milestone, mix, busy, onUse, onKeep }: {
  roomId: string; me: MeView; character: CharacterView; fallbackReason: string | null; milestone: number; mix: string | null; busy: boolean; onUse: () => void; onKeep: () => void
}) {
  const img = useCharacterImage(roomId, character.id)
  const using = me.member.avatarCharacterId === character.id
  const fb = giftResultText(character.isFallback, fallbackReason)
  return (
    <div className={sh.giftStage} style={{ gap: 14 }}>
      <span className={sh.goldText}>✦ {milestone}곡 선물</span>
      <div className={sh.characterBig}>
        {img ? <img src={img} alt={`${me.member.nickname}님 취향으로 만든 캐릭터`} /> : <Spinner size={28} />}
      </div>
      <h2 className={sh.giftTitle}>{character.isFallback ? fb.title : `${me.member.nickname}님 취향을 닮은 캐릭터예요`}</h2>
      <p className={sh.giftText}>
        {character.isFallback
          ? fb.text
          : mix ? `${mix}을 담은 ${me.member.nickname}님 취향으로 만들었어요` : `담은 노래 ${milestone}곡의 장르와 분위기로 만들었어요.`}
      </p>
      <div className={sh.compare} aria-hidden="true">
        <span><Avatar nickname={me.member.nickname} colorIdx={me.member.colorIdx} characterId={me.member.avatarCharacterId} size={28} />지금</span>
        <span>→</span>
        <span><Avatar nickname={me.member.nickname} colorIdx={me.member.colorIdx} characterId={character.id} size={28} />바꾸면</span>
      </div>
      {using ? (
        <span className={sh.center}>이미 이 캐릭터를 얼굴로 쓰고 있어요</span>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10, width: '100%' }}>
          <button type="button" className={s.primary} style={{ height: 52, fontSize: 16 }} onClick={onUse} disabled={busy}>{busy ? <Spinner /> : '이 캐릭터로 바꾸기'}</button>
          <button type="button" className={s.secondary} onClick={onKeep}>지금 그대로 둘래요</button>
        </div>
      )}
      <span className={sh.center}>받은 캐릭터는 내 정보에 모여요 · 다음 선물은 {milestone + 50}곡에서</span>
    </div>
  )
}

function GiftBoxArt() {
  return (
    <svg width="200" height="200" viewBox="0 0 240 240" aria-hidden="true" className={sh.giftArt}>
      <circle className={sh.giftGlow} cx="120" cy="130" r="96" fill="#B9A2FF" opacity="0.4" />
      <circle cx="120" cy="130" r="70" fill="#141225" opacity="0.55" />
      <g className={sh.giftBob}>
        <rect x="72" y="108" width="96" height="74" rx="10" fill="#3A2F6B" />
        <rect x="64" y="92" width="112" height="26" rx="8" fill="#5A4AA0" />
        <rect x="112" y="92" width="16" height="90" fill="#E3A6B8" />
        <path d="M120 92c-14-22-38-18-30-4 5 8 20 6 30 4zM120 92c14-22 38-18 30-4-5 8-20 6-30 4z" fill="#E3A6B8" />
        <circle cx="120" cy="92" r="6" fill="#FFE3B8" />
      </g>
      <path className={sh.twinkle} d="M50 70l2 6 6 2-6 2-2 6-2-6-6-2 6-2z" fill="#FFE3B8" />
      <path className={sh.twinkle} style={{ animationDelay: '.6s' }} d="M190 96l2 6 6 2-6 2-2 6-2-6-6-2 6-2z" fill="#CDBBFF" />
      <path className={sh.twinkle} style={{ animationDelay: '1.2s' }} d="M176 190l2 6 6 2-6 2-2 6-2-6-6-2 6-2z" fill="#FFE3B8" />
    </svg>
  )
}
