import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { memberColor, roomApi, RoomApiError, type GiftsResponse, type MeView, type MemberRow, type RoomSummary } from '../roomApi'
import { Avatar } from '../components/Avatar'
import { Spinner } from '../components/RoomPage'
import { hasBatchim, iAm } from '../text'
import { Sheet } from './Sheet'
import type { ToastData } from './Toast'
import a from './account.module.css'

type Show = (t: Omit<ToastData, 'id'>) => void
const errText = (err: unknown, fallback: string) => (err instanceof RoomApiError ? err.message : fallback)

/** 사람 색 고리 얼굴 (참가자 판과 같은 언어) */
function RingFace({ nickname, colorIdx, characterId, size }: { nickname: string; colorIdx: number; characterId?: number | null; size: number }) {
  const c = memberColor(colorIdx).bg
  return (
    <span className={a.ring} style={{ boxShadow: `0 0 0 2px ${c}, 0 0 16px color-mix(in srgb, ${c} 45%, transparent)` }}>
      <Avatar nickname={nickname} colorIdx={colorIdx} characterId={characterId} size={size} />
    </span>
  )
}

const Icon = ({ d, size = 18 }: { d: ReactNode; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{d}</svg>
)
const KEY = <><circle cx="8" cy="15" r="4" /><path d="M11 12l9-9M17 6l3 3M15 8l2 2" /></>
const PEN = <path d="M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4" />
const DOOR = <><path d="M4 21h16M6 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16" /><circle cx="14.5" cy="12" r=".9" fill="currentColor" /></>
const SPARK = <path d="M12 3l1.8 5.4L19 10l-5.2 1.6L12 17l-1.8-5.4L5 10l5.2-1.6z" />
const LOCK = <><rect x="5" y="11" width="14" height="10" rx="2.5" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></>
const DOT = <circle cx="12" cy="12" r="3.5" fill="currentColor" stroke="none" />

/** 6-h 내 정보: 닉네임 바꾸기, 받은 캐릭터로 얼굴 고르기, 이 기기에서 나가기 */
export function MeSheet({ roomId, me, onClose, onRenamed, onLeft, onError, show }: {
  roomId: string; me: MeView; onClose: () => void; onRenamed: () => void; onLeft: () => void; onError: (e: unknown) => boolean; show: Show
}) {
  const [nickname, setNickname] = useState(me.member.nickname)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [leaving, setLeaving] = useState(false)
  const nick = nickname.trim()
  const unchanged = nick === me.member.nickname
  const color = memberColor(me.member.colorIdx).bg

  async function save(e: FormEvent) {
    e.preventDefault()
    if (nick.length < 1 || nick.length > 12) { setError('닉네임은 1~12자예요'); return }
    setBusy(true); setError(null)
    try {
      await roomApi.rename(roomId, nick)
      show({ title: '닉네임을 바꿨어요', sub: `${nick} · 친구들 화면에도 바로 바뀌어요` })
      onRenamed()
    } catch (err) {
      if (onError(err)) return
      setError(err instanceof RoomApiError && err.code === 'NICKNAME_TAKEN' ? '이 우주에 같은 닉네임이 있어요' : errText(err, '바꾸지 못했어요'))
    } finally {
      setBusy(false)
    }
  }

  async function leave() {
    setLeaving(true)
    try { await roomApi.leave(roomId) } catch { /* 열쇠가 이미 무효여도 이 기기에서는 지운다 */ }
    onLeft()
  }

  return (
    <Sheet title={me.member.nickname} onClose={onClose}
      head={<RingFace nickname={me.member.nickname} colorIdx={me.member.colorIdx} characterId={me.member.avatarCharacterId} size={56} />}
      sub={<span className={a.sub}>
        <span className={a.chip}><i style={{ background: color }} aria-hidden="true" />내 색</span>
        <span className={a.chip}>노래 {me.songCount}곡</span>
        <span>{me.roomName}</span>
      </span>}>
      <form className={a.card} style={{ ['--tint' as string]: color }} onSubmit={save} noValidate>
        <h3 className={a.cardHead}><Icon d={PEN} />이름 바꾸기</h3>
        <div className={a.field}>
          <label htmlFor="me-nick" className={a.label}>닉네임</label>
          <div className={a.row}>
            <input id="me-nick" className={a.input} maxLength={12} value={nickname} autoComplete="off" aria-invalid={!!error || undefined}
              aria-describedby="me-nick-help" onChange={e => setNickname(e.target.value)} />
            <button type="submit" className={a.save} disabled={busy || unchanged}>{busy ? <Spinner size={14} /> : '저장'}</button>
          </div>
          {error ? <p className={a.error} role="alert" id="me-nick-help">{error}</p> : <span className={a.help} id="me-nick-help">12자까지, 이 우주 안에서 겹치지 않게 지어요. 친구들 화면에도 바로 바뀌어요</span>}
        </div>
      </form>

      <Characters roomId={roomId} me={me} onChanged={onRenamed} onError={onError} show={show} />

      <div className={a.card}>
        <button type="button" className={a.action} onClick={leave} disabled={leaving}>
          <span className={a.icon}><Icon d={KEY} /></span>
          <span className={a.grow}>
            이 기기에서 나가기
            <small>친구 폰이나 PC에서 들어왔다면 눌러 주세요. 내 노래는 그대로 남고, 다시 들어올 땐 "{iAm(me.member.nickname)}"와 4자리 암호면 돼요</small>
          </span>
          {leaving ? <Spinner size={16} /> : <Icon d={<path d="M9 6l6 6-6 6" />} size={16} />}
        </button>
      </div>
    </Sheet>
  )
}

/** 6-h 받은 캐릭터: 기본 얼굴 + 받은 캐릭터, 누르면 그 얼굴로. 다음 캐릭터까지 남은 곡 */
function Characters({ roomId, me, onChanged, onError, show }: { roomId: string; me: MeView; onChanged: () => void; onError: (e: unknown) => boolean; show: Show }) {
  const [data, setData] = useState<GiftsResponse | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    const ac = new AbortController()
    roomApi.gifts(roomId, ac.signal).then(setData).catch(err => { if (!ac.signal.aborted) onError(err) })
    return () => ac.abort()
  }, [roomId, onError, me.member.avatarCharacterId])

  async function choose(characterId: number | null) {
    if (characterId === (data?.avatarCharacterId ?? null)) return
    setBusy(true)
    try {
      await roomApi.setAvatar(roomId, characterId)
      show({ title: characterId == null ? '기본 얼굴로 바꿨어요' : '이 캐릭터로 바꿨어요' })
      onChanged()
    } catch (err) {
      if (!onError(err)) show({ title: '바꾸지 못했어요', sub: errText(err, ''), tone: 'error' })
    } finally {
      setBusy(false)
    }
  }

  if (!data) return null
  const current = data.avatarCharacterId
  const sum = data.summary
  return (
    <div className={a.card}>
      <h3 className={a.cardHead}><Icon d={SPARK} />내 얼굴</h3>
      <p className={a.cardNote}>노래 50곡마다 내 취향으로 그린 캐릭터를 하나씩 받아요. 누르면 그 얼굴로 바뀌어요</p>
      <div className={a.faces} role="group" aria-label="얼굴 고르기">
        <button type="button" className={a.faceChoice} aria-pressed={current == null} aria-label="기본 얼굴" disabled={busy} onClick={() => choose(null)}>
          <span style={{ borderRadius: '50%', display: 'inline-flex' }}><Avatar nickname={me.member.nickname} colorIdx={me.member.colorIdx} size={48} /></span>
        </button>
        {data.characters.map((c, i) => (
          <button key={c.id} type="button" className={a.faceChoice} aria-pressed={current === c.id} aria-label={`받은 캐릭터 ${i + 1}${c.isFallback ? ' (기본 캐릭터)' : ''}`} disabled={busy} onClick={() => choose(c.id)}>
            <span style={{ borderRadius: '50%', display: 'inline-flex' }}><Avatar nickname={me.member.nickname} colorIdx={me.member.colorIdx} characterId={c.id} size={48} /></span>
          </button>
        ))}
        <span className={a.nextSlot} role="img" aria-label={`다음 캐릭터까지 ${sum.remaining}곡`}>+{sum.remaining}곡</span>
      </div>
      {sum.receivedToday && <span className={a.goldNote}>오늘 선물은 받았어요. {sum.nextMilestone}곡이 되면 {sum.waiting > 0 ? '내일 열려요' : '다음 날 열려요'}</span>}
      <span className={a.help}>노래를 빼도 받은 캐릭터는 남아요</span>
    </div>
  )
}

/** 6-c 방장: 참가자 새 4자리(한 번만 보여 줌)·내보내기 */
export function MemberSheet({ roomId, member, onClose, onKicked, onError, show }: {
  roomId: string; member: MemberRow; onClose: () => void; onKicked: () => void; onError: (e: unknown) => boolean; show: Show
}) {
  const [pin, setPin] = useState<string | null>(null)
  const [busy, setBusy] = useState<'pin' | 'kick' | null>(null)
  const [confirmKick, setConfirmKick] = useState(false)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const openRef = useRef<HTMLButtonElement>(null)
  const color = memberColor(member.colorIdx).bg
  // 확인이 열리면 "취소"로, 닫히면 연 버튼으로 초점을 옮긴다 (실수로 Enter를 눌러도 지워지지 않게)
  const prevConfirm = useRef(confirmKick)
  useEffect(() => {
    if (prevConfirm.current === confirmKick) return
    prevConfirm.current = confirmKick
    if (confirmKick) cancelRef.current?.focus()
    else openRef.current?.focus()
  }, [confirmKick])

  async function newPin() {
    setBusy('pin')
    try {
      const r = await roomApi.resetPin(roomId, member.id)
      setPin(r.pin)
    } catch (err) {
      if (!onError(err)) show({ title: '새 암호를 만들지 못했어요', sub: errText(err, ''), tone: 'error' })
    } finally {
      setBusy(null)
    }
  }

  async function copy() {
    if (!pin) return
    try { await navigator.clipboard.writeText(pin); show({ title: '숫자를 복사했어요', sub: `${member.nickname}님에게 보내 주세요` }) }
    catch { show({ title: '복사하지 못했어요', sub: '숫자를 직접 보내 주세요', tone: 'error' }) }
  }

  async function kick() {
    setBusy('kick')
    try {
      await roomApi.kick(roomId, member.id)
      show({ title: `${member.nickname}님을 내보냈어요`, sub: '담은 노래도 함께 지웠어요' })
      onKicked()
    } catch (err) {
      if (!onError(err)) show({ title: '내보내지 못했어요', sub: errText(err, ''), tone: 'error' })
      setBusy(null)
    }
  }

  return (
    <Sheet title={member.nickname} onClose={onClose}
      head={<RingFace nickname={member.nickname} colorIdx={member.colorIdx} characterId={member.avatarCharacterId} size={56} />}
      sub={<span className={a.sub}><span className={a.chip}><i style={{ background: color }} aria-hidden="true" />노래 {member.songCount}곡</span></span>}>
      <div className={a.card} style={{ ['--tint' as string]: color }}>
        <h3 className={a.cardHead}><Icon d={LOCK} />새 암호</h3>
        {pin ? (
          <>
            <div className={a.pin} role="img" aria-label={`새 암호 ${pin.split('').join(' ')}`}>
              {pin.split('').map((d, i) => <span key={i} aria-hidden="true">{d}</span>)}
            </div>
            <p className={a.pinNote}>이 숫자를 {member.nickname}님에게 보내 주세요. 이전 암호는 더 이상 안 되고, 이 화면을 닫으면 다시 볼 수 없어요</p>
            <button type="button" className={a.cta} onClick={copy}>숫자 복사</button>
            <button type="button" className={a.quiet} onClick={newPin} disabled={busy !== null}>{busy === 'pin' ? <Spinner size={14} /> : '다른 숫자로 다시 만들기'}</button>
          </>
        ) : (
          <>
            <p className={a.cardNote}>{member.nickname}님이 암호를 잊었다면 새 4자리를 만들어 보내 주세요. 틀린 횟수와 잠금도 함께 풀려요</p>
            <button type="button" className={a.cta} onClick={newPin} disabled={busy !== null}>{busy === 'pin' ? <Spinner /> : '새 암호 만들기'}</button>
          </>
        )}
      </div>

      <div className={a.danger}>
        <h3 className={a.dangerHead}>내보내기</h3>
        {!confirmKick ? (
          <>
            <p className={a.cardNote}>{member.nickname}님과 담은 노래 {member.songCount}곡이 이 우주에서 사라져요</p>
            <button ref={openRef} type="button" className={a.dangerOpen} onClick={() => setConfirmKick(true)}>{member.nickname}님 내보내기</button>
          </>
        ) : (
          <div role="group" aria-labelledby="kick-q" className={a.confirm}>
            <p id="kick-q" className={a.cardNote} style={{ color: 'var(--r-text)' }}>{member.nickname}님이 담은 노래 {member.songCount}곡도 함께 지워져요. 내보낼까요?</p>
            <div className={a.buttons}>
              <button ref={cancelRef} type="button" className={a.cancel} onClick={() => setConfirmKick(false)}>취소</button>
              <button type="button" className={a.dangerGo} onClick={kick} disabled={busy !== null}>{busy === 'kick' ? <Spinner /> : '내보내기'}</button>
            </div>
          </div>
        )}
      </div>
    </Sheet>
  )
}

/** 6-e 방 설정 (이름·참가 마감) → 6-f 삭제 확인 */
export function SettingsSheet({ roomId, summary, memberCount, songCount, onClose, onChanged, onDeleted, onError, show }: {
  roomId: string; summary: RoomSummary; memberCount: number; songCount: number
  onClose: () => void; onChanged: () => void; onDeleted: () => void; onError: (e: unknown) => boolean; show: Show
}) {
  const [name, setName] = useState(summary.name)
  const [closed, setClosed] = useState(summary.status === 'CLOSED')
  const [busy, setBusy] = useState<'name' | 'closed' | 'delete' | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const cancelRef = useRef<HTMLButtonElement>(null)
  useEffect(() => { if (confirmDelete) cancelRef.current?.focus() }, [confirmDelete])

  async function saveName(e: FormEvent) {
    e.preventDefault()
    const n = name.trim()
    if (n.length < 1 || n.length > 30) { setError('우주 이름은 1~30자예요'); return }
    setBusy('name'); setError(null)
    try {
      await roomApi.patchRoom(roomId, { name: n })
      show({ title: '우주 이름을 바꿨어요', sub: n })
      onChanged()
    } catch (err) {
      if (!onError(err)) setError(errText(err, '바꾸지 못했어요'))
    } finally {
      setBusy(null)
    }
  }

  async function toggleClosed(next: boolean) {
    setBusy('closed'); setClosed(next)
    try {
      await roomApi.patchRoom(roomId, { closed: next })
      show({ title: next ? '새 참가를 마감했어요' : '다시 참가할 수 있어요', sub: next ? '이미 들어온 사람은 그대로 들어올 수 있어요' : undefined })
      onChanged()
    } catch (err) {
      setClosed(!next)
      if (!onError(err)) show({ title: '바꾸지 못했어요', sub: errText(err, ''), tone: 'error' })
    } finally {
      setBusy(null)
    }
  }

  async function remove() {
    setBusy('delete')
    try {
      await roomApi.deleteRoom(roomId)
      onDeleted()
    } catch (err) {
      if (!onError(err)) show({ title: '삭제하지 못했어요', sub: errText(err, ''), tone: 'error' })
      setBusy(null)
    }
  }

  if (confirmDelete) {
    return (
      <Sheet title={<>'{summary.name}'{hasBatchim(summary.name) ? '을' : '를'}<br />삭제할까요?</>} onClose={() => setConfirmDelete(false)}>
        <div className={a.confirm}>
          <FadingPlanet />
          <ul className={a.loss}>
            <li><Icon d={DOT} size={14} />담긴 노래 {songCount}곡과 지도가 사라져요</li>
            <li><Icon d={DOT} size={14} />참가자 {memberCount}명이 더 이상 들어올 수 없어요</li>
            <li><Icon d={DOT} size={14} />방장 이메일도 함께 지워져요</li>
            <li className={a.lossStrong}><Icon d={DOT} size={14} />바로 닫히고 7일 뒤 완전히 지워져요</li>
          </ul>
          <div className={a.buttons}>
            <button ref={cancelRef} type="button" className={a.cancel} onClick={() => setConfirmDelete(false)}>취소</button>
            <button type="button" className={a.dangerGo} onClick={remove} disabled={busy !== null}>{busy === 'delete' ? <Spinner /> : '삭제하기'}</button>
          </div>
        </div>
      </Sheet>
    )
  }

  return (
    <Sheet title="방 설정" onClose={onClose} sub={<span className={a.sub}><span className={a.chip}>노래 {songCount}곡</span><span className={a.chip}>{memberCount} / {summary.capacity}명</span></span>}>
      <form className={a.card} onSubmit={saveName} noValidate>
        <h3 className={a.cardHead}><Icon d={PEN} />우주 이름</h3>
        <div className={a.field}>
          <label htmlFor="room-rename" className={a.label}>이름</label>
          <div className={a.row}>
            <input id="room-rename" className={a.input} maxLength={30} value={name} autoComplete="off" aria-invalid={!!error || undefined}
              aria-describedby={error ? 'room-rename-err' : undefined} onChange={e => setName(e.target.value)} />
            <button type="submit" className={a.save} disabled={busy !== null || name.trim() === summary.name}>{busy === 'name' ? <Spinner size={14} /> : '저장'}</button>
          </div>
          {error && <p className={a.error} role="alert" id="room-rename-err">{error}</p>}
        </div>
      </form>

      <div className={a.card}>
        <label className={a.action} style={{ cursor: 'pointer' }}>
          <span className={a.icon}><Icon d={DOOR} /></span>
          <span className={a.grow}>
            새 참가 마감
            <small>켜면 링크로 새로 들어올 수 없어요. 이미 들어온 사람은 그대로예요</small>
          </span>
          <input type="checkbox" role="switch" className={a.switch} checked={closed} disabled={busy !== null} onChange={e => toggleClosed(e.target.checked)} />
        </label>
        <div className={a.seats} role="img" aria-label={`지금 ${memberCount}명, 최대 ${summary.capacity}명`}>
          {Array.from({ length: summary.capacity }, (_, i) => <i key={i} className={i < memberCount ? a.seatOn : a.seatOff} />)}
        </div>
      </div>

      <div className={a.danger}>
        <h3 className={a.dangerHead}>우주 삭제</h3>
        <p className={a.cardNote}>삭제하면 모든 노래와 참가자 정보가 지워져요. 확인을 한 번 더 받아요</p>
        <button type="button" className={a.dangerOpen} onClick={() => setConfirmDelete(true)}>우주 삭제하기</button>
      </div>
    </Sheet>
  )
}

/** 삭제 확인: 빛이 꺼져 가는 행성 */
function FadingPlanet() {
  return (
    <svg className={a.fade} width="132" height="96" viewBox="0 0 132 96" aria-hidden="true">
      <ellipse cx="66" cy="52" rx="58" ry="16" fill="none" stroke="rgba(227,166,184,.35)" strokeDasharray="2 6" strokeLinecap="round" />
      <circle cx="66" cy="48" r="26" fill="#2a2450" />
      <path d="M66 22a26 26 0 0 1 0 52a18 26 0 0 0 0-52z" fill="#141225" opacity=".85" />
      <circle cx="18" cy="56" r="3" fill="rgba(227,166,184,.6)" />
      <circle cx="112" cy="46" r="2.4" fill="rgba(230,220,255,.35)" />
      <circle cx="98" cy="62" r="2" fill="rgba(230,220,255,.2)" />
    </svg>
  )
}
