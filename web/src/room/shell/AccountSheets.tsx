import { useEffect, useState, type FormEvent } from 'react'
import { roomApi, RoomApiError, type GiftsResponse, type MeView, type MemberRow, type RoomSummary } from '../roomApi'
import { Avatar } from '../components/Avatar'
import { Spinner } from '../components/RoomPage'
import { hasBatchim, iAm } from '../text'
import { Sheet } from './Sheet'
import type { ToastData } from './Toast'
import s from '../room.module.css'
import sh from './shell.module.css'

type Show = (t: Omit<ToastData, 'id'>) => void
const errText = (err: unknown, fallback: string) => (err instanceof RoomApiError ? err.message : fallback)

/** 6-h 내 정보: 닉네임 바꾸기, 이 기기에서 나가기 (받은 캐릭터는 6단계) */
export function MeSheet({ roomId, me, onClose, onRenamed, onLeft, onError, show }: {
  roomId: string; me: MeView; onClose: () => void; onRenamed: () => void; onLeft: () => void; onError: (e: unknown) => boolean; show: Show
}) {
  const [nickname, setNickname] = useState(me.member.nickname)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [leaving, setLeaving] = useState(false)
  const nick = nickname.trim()
  const unchanged = nick === me.member.nickname

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
    <Sheet title="내 정보" onClose={onClose}
      head={<Avatar nickname={me.member.nickname} colorIdx={me.member.colorIdx} characterId={me.member.avatarCharacterId} size={48} />}
      sub={<span className={sh.songSub}>{me.roomName} · 노래 {me.songCount}곡</span>}>
      <form className={sh.panel} onSubmit={save} noValidate>
        <label htmlFor="me-nick" className={s.label}>닉네임</label>
        <div className={sh.inline}>
          <input id="me-nick" className={s.input} style={{ height: 48 }} maxLength={12} value={nickname} aria-invalid={!!error || undefined} onChange={e => setNickname(e.target.value)} />
          <button type="submit" className={sh.smallPrimary} disabled={busy || unchanged}>{busy ? <Spinner size={14} /> : '저장'}</button>
        </div>
        {error ? <p className={s.error} role="alert">{error}</p> : <span className={s.hint}>12자까지 · 이 우주 안에서 겹치지 않게 · 친구들 화면에도 바로 바뀌어요</span>}
      </form>

      <Characters roomId={roomId} me={me} onChanged={onRenamed} onError={onError} show={show} />

      <div className={sh.panel}>
        <button type="button" className={sh.rowButton} onClick={leave} disabled={leaving}>
          이 기기에서 나가기
          {leaving ? <Spinner size={16} /> : <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#8F87B0" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>}
        </button>
        <span className={s.hint} style={{ lineHeight: 1.6 }}>
          친구 폰이나 PC에서 들어왔다면 눌러 주세요. 내 노래는 그대로 남고, 다시 들어올 땐 "{iAm(me.member.nickname)}" + 4자리 암호를 쓰면 돼요.
        </span>
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
    <div className={sh.panel}>
      <span style={{ fontSize: 14, fontWeight: 500 }}>받은 캐릭터 <span style={{ fontWeight: 400, color: 'var(--r-text-3)' }}>· 50곡마다 하나씩</span></span>
      <div className={sh.characters} role="group" aria-label="얼굴 고르기">
        <button type="button" className={sh.characterChoice} aria-pressed={current == null} aria-label="기본 얼굴" disabled={busy} onClick={() => choose(null)}>
          <Avatar nickname={me.member.nickname} colorIdx={me.member.colorIdx} size={48} />
        </button>
        {data.characters.map((c, i) => (
          <button key={c.id} type="button" className={sh.characterChoice} aria-pressed={current === c.id} aria-label={`받은 캐릭터 ${i + 1}${c.isFallback ? ' (기본 캐릭터)' : ''}`} disabled={busy} onClick={() => choose(c.id)}>
            <Avatar nickname={me.member.nickname} colorIdx={me.member.colorIdx} characterId={c.id} size={48} />
          </button>
        ))}
        <span className={sh.nextSlot} aria-label={`다음 캐릭터까지 ${sum.remaining}곡`}>+{sum.remaining}곡</span>
      </div>
      {sum.receivedToday && <span style={{ fontSize: 12, color: 'var(--r-peach)' }}>오늘 선물은 받았어요 · {sum.nextMilestone}곡이 되면 {sum.waiting > 0 ? '내일 열려요' : '다음 날 열려요'}</span>}
      <span className={s.hint}>누르면 그 캐릭터로 바뀌어요 · 노래를 빼도 받은 캐릭터는 남아요</span>
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
      head={<Avatar nickname={member.nickname} colorIdx={member.colorIdx} characterId={member.avatarCharacterId} size={48} />}
      sub={<span className={sh.songSub}>노래 {member.songCount}곡</span>}>
      <div className={sh.panel}>
        <strong style={{ fontSize: 14, fontWeight: 500 }}>{member.nickname}님 새 암호</strong>
        {pin ? (
          <>
            <span className={sh.bigPin} aria-label={`새 암호 ${pin.split('').join(' ')}`}>{pin}</span>
            <span className={s.hint} style={{ textAlign: 'center' }}>이 숫자를 {member.nickname}님에게 보내 주세요 · 이전 암호는 더 이상 안 돼요<br />이 화면을 닫으면 다시 볼 수 없어요</span>
            <button type="button" className={s.primary} style={{ height: 46, fontSize: 15 }} onClick={copy}>숫자 복사</button>
          </>
        ) : (
          <>
            <span className={s.hint}>암호를 잊었다고 하면 새 4자리를 만들어 보내 주세요.</span>
            <button type="button" className={s.primary} style={{ height: 46, fontSize: 15 }} onClick={newPin} disabled={busy !== null}>
              {busy === 'pin' ? <Spinner /> : '새 암호 만들기'}
            </button>
          </>
        )}
      </div>
      <span className={s.hint}>새 암호를 만들면 {member.nickname}님의 틀린 횟수와 잠금도 함께 풀려요 · 다시 누르면 다른 숫자가 나와요</span>
      {pin && <button type="button" className={s.ghost} onClick={newPin} disabled={busy !== null}>다른 숫자로 다시 만들기</button>}

      <div className={sh.divider}>
        {!confirmKick ? (
          <button type="button" className={sh.dangerRow} onClick={() => setConfirmKick(true)}>{member.nickname}님 내보내기</button>
        ) : (
          <div className={sh.panel} role="group" aria-label="내보내기 확인">
            <span style={{ fontSize: 14 }}>{member.nickname}님이 담은 노래 {member.songCount}곡도 함께 지워져요. 내보낼까요?</span>
            <div className={sh.dialogButtons}>
              <button type="button" className={sh.dangerButton} onClick={kick} disabled={busy !== null}>{busy === 'kick' ? <Spinner /> : '내보내기'}</button>
              <button type="button" className={s.secondary} style={{ height: 44 }} onClick={() => setConfirmKick(false)}>취소</button>
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
        <ul className={sh.panel} style={{ margin: 0, listStyle: 'none', fontSize: 14, color: 'var(--r-silver)', gap: 8 }}>
          <li>· 담긴 노래 {songCount}곡과 지도가 사라져요</li>
          <li>· 참가자 {memberCount}명이 더 이상 들어올 수 없어요</li>
          <li>· 방장 이메일도 함께 지워져요</li>
          <li style={{ color: 'var(--r-error)' }}>· 바로 닫히고 7일 뒤 완전히 지워져요</li>
        </ul>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <button type="button" className={sh.dangerButton} style={{ height: 52, fontSize: 16 }} onClick={remove} disabled={busy !== null}>{busy === 'delete' ? <Spinner /> : '삭제하기'}</button>
          <button type="button" className={s.secondary} onClick={() => setConfirmDelete(false)}>취소</button>
        </div>
      </Sheet>
    )
  }

  return (
    <Sheet title="방 설정" onClose={onClose}>
      <form className={s.field} onSubmit={saveName} noValidate>
        <label htmlFor="room-rename" className={s.label}>우주 이름</label>
        <div className={sh.inline}>
          <input id="room-rename" className={s.input} style={{ height: 50 }} maxLength={30} value={name} aria-invalid={!!error || undefined} onChange={e => setName(e.target.value)} />
          <button type="submit" className={sh.smallSecondary} disabled={busy !== null || name.trim() === summary.name}>{busy === 'name' ? <Spinner size={14} /> : '저장'}</button>
        </div>
        {error && <p className={s.error} role="alert">{error}</p>}
      </form>

      <label className={sh.switchRow}>
        <span className={sh.personText}>
          <strong style={{ fontSize: 15, fontWeight: 500 }}>새 참가 마감</strong>
          <span className={sh.songSub} style={{ whiteSpace: 'normal' }}>켜면 링크로 새로 들어올 수 없어요 · 지금 {memberCount} / {summary.capacity}명</span>
        </span>
        <input type="checkbox" role="switch" className={sh.switch} checked={closed} disabled={busy !== null} onChange={e => toggleClosed(e.target.checked)} />
      </label>

      <div className={sh.divider}>
        <button type="button" className={sh.dangerRow} onClick={() => setConfirmDelete(true)}>우주 삭제하기</button>
        <span className={s.hint}>삭제하면 모든 노래와 참가자 정보가 지워져요</span>
      </div>
    </Sheet>
  )
}
