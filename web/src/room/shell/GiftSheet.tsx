import { useEffect, useRef, useState } from 'react'
import { roomApi, RoomApiError, type CharacterView, type GiftBoxDetail, type GiftBoxView, type MeView } from '../roomApi'
import { Avatar, useCharacterImage } from '../components/Avatar'
import { genreMixText, giftResultText, imageQuota } from '../roomLogic'
import { Spinner } from '../components/RoomPage'
import { Sheet } from './Sheet'
import type { ToastData } from './Toast'
import { genreColor } from '../galaxy/faces'
import s from '../room.module.css'
import sh from './shell.module.css'
import g from './gift.module.css'

/**
 * 4-j 선물 상자 → 열기 → 캐릭터 만드는 중 → 4-k 새 캐릭터 받기 · 바꿀까요.
 * 열면 worker가 만든다(로컬 가짜 모드는 기본 그림, 1초 안). 2초마다 상자 상태를 본다. 닫아도 계속 만들어진다.
 * 11-14 개편: 상자는 지도 행성처럼 궤도 위에(50곡 = 위성 10개). 열면 뚜껑이 떠오르고 빛기둥, 이 판에서 연 캐릭터는 궤도 안에서 떠오른다.
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
  // 이 판에서 상자가 닫혀 있던 걸 봤으면, 열린 순간 캐릭터가 떠오른다 (다시 열어 본 상자는 그대로)
  const sawClosed = useRef(false)
  if (status === 'READY' || status === 'GENERATING') sawClosed.current = true
  // ⑯ 변경(11-14): 오늘 남은 새 그림 (상자 상세 → 없으면 내 정보 요약, 둘 다 없으면 표시 생략)
  const quota = imageQuota(detail?.imagesLeftToday ?? me.gift?.imagesLeftToday, detail?.imageDailyLimit ?? me.gift?.imageDailyLimit)
  const fallbackReason = detail?.fallbackReason ?? character?.fallbackReason ?? null
  const opening = status === 'GENERATING' || (status === 'READY' && busy)
  return (
    <Sheet title={status === 'OPENED' ? '새 캐릭터가 왔어요' : status === 'WAITING' ? '기다리는 선물 상자' : `내 노래 ${milestone}곡 선물`} onClose={onClose}>
      {!box && !error && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
      {error && <p className={s.error} role="alert">{error}</p>}

      {(status === 'READY' || status === 'GENERATING') && (
        <div className={g.stage}>
          <BoxSky milestone={milestone} mode={opening ? 'opening' : 'ready'} />
          {status === 'READY' && !busy ? (
            quota?.soldOut ? (
              <>
                <h2 className={g.title}>오늘 새 그림은 다 나갔어요</h2>
                <p className={g.text}>기본 캐릭터를 지금 받거나, 내일 새 그림으로 열 수 있어요. 내일 열기를 고르면 상자는 그대로 남아요.</p>
                <span className={`${g.quota} ${g.quotaOut}`}>{quota.text}</span>
                <div className={g.actions}>
                  <button type="button" className={g.cta} onClick={open} disabled={busy}>기본 캐릭터로 지금 받기</button>
                  <button type="button" className={s.secondary} onClick={onClose} disabled={busy}>내일 새 그림으로 열기</button>
                </div>
              </>
            ) : (
              <>
                <h2 className={g.title}>{me.member.nickname}님 취향으로<br />캐릭터를 만들어 드려요</h2>
                <p className={g.text}>담은 노래의 장르와 분위기로 하나뿐인 캐릭터를 그려요</p>
                {quota && <span className={g.quota}><Sparkle />{quota.text}</span>}
                <div className={g.actions}>
                  <button type="button" className={g.cta} onClick={open} disabled={busy}>상자 열기</button>
                </div>
              </>
            )
          ) : (
            <>
              <h2 className={g.title}>{me.member.nickname}님 취향으로<br />캐릭터를 만들고 있어요</h2>
              <p className={g.text}>담은 노래 {milestone}곡의 장르와 분위기를 읽는 중이에요</p>
              <span role="status" aria-label="만드는 중" className={g.dots}><span /><span /><span /></span>
              <button type="button" className={g.quiet} onClick={onClose}>닫아도 돼요 · 다 만들면 내 노래 탭에서 볼 수 있어요</button>
            </>
          )}
        </div>
      )}

      {status === 'OPENED' && character && (
        <Pick roomId={roomId} me={me} character={character} fallbackReason={fallbackReason} milestone={milestone} mix={mix} genreMix={detail?.genreMix ?? []}
          reveal={sawClosed.current} busy={busy} onUse={useCharacter} onKeep={onClose} />
      )}

      {status === 'FAILED' && (
        <div className={g.stage}>
          <h2 className={g.title}>캐릭터를 만들지 못했어요</h2>
          <p className={g.text}>잠시 뒤 다시 열어 주세요. 상자는 사라지지 않아요.</p>
        </div>
      )}
      {status === 'WAITING' && (
        <div className={g.stage}>
          <BoxSky milestone={milestone} mode="waiting" />
          <h2 className={g.title}>이 상자는 내일 열려요</h2>
          <p className={g.text}>선물 상자는 하루에 하나씩 열 수 있어요. 오늘 상자는 이미 받았으니, 내일 다시 들어오면 이 상자가 열릴 준비를 해요.</p>
          <button type="button" className={g.quiet} onClick={onClose}>알겠어요</button>
        </div>
      )}
    </Sheet>
  )
}

/** 4-k 새 캐릭터 받기 · 바꿀까요 */
function Pick({ roomId, me, character, fallbackReason, milestone, mix, genreMix, reveal, busy, onUse, onKeep }: {
  roomId: string; me: MeView; character: CharacterView; fallbackReason: string | null; milestone: number; mix: string | null
  genreMix: { galaxy: string; songs: number }[]; reveal: boolean; busy: boolean; onUse: () => void; onKeep: () => void
}) {
  const img = useCharacterImage(roomId, character.id)
  const using = me.member.avatarCharacterId === character.id
  const fb = giftResultText(character.isFallback, fallbackReason)
  const parts = genreMix.filter(x => x.galaxy && x.songs > 0).slice(0, 4)
  const max = Math.max(1, ...parts.map(x => x.songs))
  return (
    <div className={`${g.stage} ${reveal ? g.rise : ''}`}>
      <div className={g.reveal}>
        <RevealRing />
        <div className={g.character}>
          {img ? <img src={img} alt={`${me.member.nickname}님 취향으로 만든 캐릭터`} /> : <Spinner size={28} />}
        </div>
      </div>
      <h2 className={g.title}>{character.isFallback ? fb.title : `${me.member.nickname}님 취향을 닮은 캐릭터예요`}</h2>
      {character.isFallback ? (
        <p className={g.text}>{fb.text}</p>
      ) : parts.length > 0 ? (
        <section className={g.mix} aria-label="이 캐릭터를 만든 장르">
          <p className={g.mixHead}>담은 노래 {milestone}곡에서 이 장르들을 섞었어요</p>
          <ul className={g.mixList}>
            {parts.map(x => (
              <li key={x.galaxy} className={g.mixRow}>
                <span>{x.galaxy}</span><b>{x.songs}곡</b>
                <span className={g.mixBar} aria-hidden="true"><i style={{ width: `${Math.round((x.songs / max) * 100)}%`, background: genreColor(x.galaxy, null) }} /></span>
              </li>
            ))}
          </ul>
          {/* 문장 하나로도 읽히게 (화면 읽기·복사) */}
          {mix && <span className={s.srOnly}>{mix}을 담은 {me.member.nickname}님 취향으로 만들었어요</span>}
        </section>
      ) : (
        <p className={g.text}>담은 노래 {milestone}곡의 장르와 분위기로 만들었어요.</p>
      )}
      <div className={g.swap} aria-hidden="true">
        <span><Avatar nickname={me.member.nickname} colorIdx={me.member.colorIdx} characterId={me.member.avatarCharacterId} size={28} />지금</span>
        <svg width="18" height="10" viewBox="0 0 18 10" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M1 5h15M12 1l4 4-4 4" /></svg>
        <span><Avatar nickname={me.member.nickname} colorIdx={me.member.colorIdx} characterId={character.id} size={28} />바꾸면</span>
      </div>
      {using ? (
        <span className={g.foot}>이미 이 캐릭터를 얼굴로 쓰고 있어요</span>
      ) : (
        <div className={g.actions}>
          <button type="button" className={g.cta} onClick={onUse} disabled={busy}>{busy ? <Spinner /> : '이 캐릭터로 바꾸기'}</button>
          <button type="button" className={s.secondary} onClick={onKeep}>지금 그대로 둘래요</button>
        </div>
      )}
      <span className={g.foot}>받은 캐릭터는 내 정보에 모여요. 다음 선물은 {milestone + 50}곡에서 받아요</span>
    </div>
  )
}

/** 궤도 위의 상자: 50곡 = 위성 10개. ready는 숨 쉬듯 빛나고, opening은 뚜껑이 떠오르며 빛기둥, waiting은 달 아래 조용히 */
function BoxSky({ milestone, mode }: { milestone: number; mode: 'ready' | 'opening' | 'waiting' }) {
  const sats = Array.from({ length: 10 }, (_, i) => {
    const a = (i / 10) * Math.PI * 2 - Math.PI / 2
    return { x: 120 + Math.cos(a) * 98, y: 132 + Math.sin(a) * 34 }
  })
  const waiting = mode === 'waiting'
  return (
    <div className={`${g.sky} ${waiting ? g.skyWaiting : ''}`}>
      <span className={g.milestone} aria-hidden="true"><b>{milestone}</b><span>곡</span></span>
      {waiting && <span className={g.moonTag}><svg width="12" height="12" viewBox="0 0 24 24" fill="#ffe3b8" aria-hidden="true"><path d="M20 15.5A8.5 8.5 0 0 1 8.5 4a8.5 8.5 0 1 0 11.5 11.5z" /></svg>내일 열려요</span>}
      <svg viewBox="0 0 240 200" className={`${g.art} ${g[mode] ?? ''}`} aria-hidden="true">
        <defs>
          <linearGradient id="gift-beam" x1="0" y1="1" x2="0" y2="0">
            <stop offset="0%" stopColor="#ffe3b8" stopOpacity=".85" />
            <stop offset="100%" stopColor="#ffe3b8" stopOpacity="0" />
          </linearGradient>
          <radialGradient id="gift-glow"><stop offset="0%" stopColor="#b9a2ff" stopOpacity=".55" /><stop offset="100%" stopColor="#b9a2ff" stopOpacity="0" /></radialGradient>
        </defs>
        {!waiting && <circle className={g.glow} cx="120" cy="118" r="78" fill="url(#gift-glow)" />}
        {/* 궤도 뒤쪽 절반 */}
        <path className={waiting ? g.orbit : g.orbitGold} d="M22 132a98 34 0 0 1 196 0" />
        <polygon className={g.beam} points="104,96 136,96 170,0 70,0" fill="url(#gift-beam)" />
        {/* 상자 */}
        <ellipse cx="120" cy="158" rx="46" ry="7" fill="#0f0d1f" opacity=".55" />
        <rect x="80" y="104" width="80" height="54" rx="9" fill={waiting ? '#2d2752' : '#3a2f6b'} />
        <rect x="113" y="104" width="14" height="54" fill={waiting ? '#7d6f9e' : '#e3a6b8'} />
        <g className={g.lid}>
          <rect x="72" y="88" width="96" height="20" rx="7" fill={waiting ? '#3e3670' : '#5a4aa0'} />
          <rect x="113" y="88" width="14" height="20" fill={waiting ? '#8d7fae' : '#e3a6b8'} />
          <path d="M120 88c-12-19-33-15-26-3 4 7 17 5 26 3zM120 88c12-19 33-15 26-3-4 7-17 5-26 3z" fill={waiting ? '#8d7fae' : '#e3a6b8'} />
          <circle cx="120" cy="88" r="5" fill={waiting ? '#c9bfe6' : '#ffe3b8'} />
        </g>
        {/* 궤도 앞쪽 절반 + 위성 (= 담은 노래) */}
        <path className={waiting ? g.orbit : g.orbitGold} d="M22 132a98 34 0 0 0 196 0" />
        <g>
          {sats.map((p, i) => <circle key={i} className={waiting ? g.satDim : g.sat} style={{ animationDelay: `${i * 0.12}s` }} cx={p.x} cy={p.y} r={i === 0 ? 4.2 : 3} />)}
        </g>
        {!waiting && <>
          <path className={g.twinkle} d="M44 54l2 6 6 2-6 2-2 6-2-6-6-2 6-2z" fill="#ffe3b8" />
          <path className={g.twinkle} style={{ animationDelay: '.7s' }} d="M196 70l2 6 6 2-6 2-2 6-2-6-6-2 6-2z" fill="#cdbbff" />
          <path className={g.twinkle} style={{ animationDelay: '1.3s' }} d="M184 20l1.5 4.5 4.5 1.5-4.5 1.5-1.5 4.5-1.5-4.5-4.5-1.5 4.5-1.5z" fill="#ffe3b8" />
        </>}
      </svg>
    </div>
  )
}

/** 캐릭터 둘레: 금빛 궤도가 그려지고 반짝임 셋 */
function RevealRing() {
  return (
    <svg viewBox="0 0 220 220" className={g.revealRing} aria-hidden="true">
      <circle cx="110" cy="110" r="102" stroke="rgba(185,162,255,.18)" strokeWidth="1" />
      <circle className={g.draw} cx="110" cy="110" r="102" stroke="#ffe3b8" strokeWidth="1.4" strokeLinecap="round" strokeDasharray="2 7" transform="rotate(-90 110 110)" />
      <path className={g.spark} style={{ animationDelay: '.7s' }} d="M30 52l2.4 7 7 2.4-7 2.4-2.4 7-2.4-7-7-2.4 7-2.4z" fill="#ffe3b8" />
      <path className={g.spark} style={{ animationDelay: '.9s' }} d="M196 74l2 6 6 2-6 2-2 6-2-6-6-2 6-2z" fill="#cdbbff" />
      <path className={g.spark} style={{ animationDelay: '1.1s' }} d="M176 192l1.6 5 5 1.6-5 1.6-1.6 5-1.6-5-5-1.6 5-1.6z" fill="#e3a6b8" />
      <circle className={g.spark} style={{ animationDelay: '.8s' }} cx="110" cy="8" r="4" fill="#ffe3b8" />
    </svg>
  )
}

function Sparkle() {
  return <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true"><path d="M6 0l1.4 4.6L12 6l-4.6 1.4L6 12 4.6 7.4 0 6l4.6-1.4z" fill="currentColor" /></svg>
}
