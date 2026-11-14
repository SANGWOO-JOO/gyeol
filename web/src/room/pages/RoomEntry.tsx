import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { keyStore } from '../keyStore'
import { RoomShell } from '../shell/RoomShell'
import { HostReset } from './HostReset'
import { memberColor, rememberJoin, roomApi, RoomApiError, type MemberView, type RoomSummary } from '../roomApi'
import { BackButton, NoteIcon, RoomPage, Spinner } from '../components/RoomPage'
import { Avatar } from '../components/Avatar'
import { CodeInput } from '../components/CodeInput'
import { Wordmark } from '../components/Wordmark'
import { iAm, minutesText } from '../text'
import s from '../room.module.css'
import o from '../components/outside.module.css'

// /rooms/:rid — 이 기기에 열쇠가 있으면 방, 없으면 초대받은 화면(5)·다시 들어오기(6-a)·비밀번호를 잊었을 때(6-b)·들어갈 수 없는 방(6-g)

type View =
  | { kind: 'invite' }
  | { kind: 'rejoin'; member: MemberView }
  | { kind: 'forgot'; member: MemberView }
  | { kind: 'hostReset' }
  | { kind: 'blocked'; reason: 'full' | 'closed' }

const AUTO_RETRIES = 2
const AUTO_RETRY_MS = 3000

export function RoomEntry() {
  const { rid = '' } = useParams()
  const [summary, setSummary] = useState<RoomSummary | null>(null)
  const [loadError, setLoadError] = useState<'gone' | 'network' | null>(null)
  // 네트워크 실패면 몇 초 뒤 저절로 두 번까지 다시 (서버 재시작 등). 그 뒤엔 [다시 시도] (I-43 ③)
  const [attempt, setAttempt] = useState(0)
  const [hasKey, setHasKey] = useState(() => keyStore.get(rid) !== null)
  // 참가·나가기 뒤 사람 목록이 바뀌므로 다시 불러온다
  const [version, setVersion] = useState(0)
  const changed = (keyNow: boolean) => { setHasKey(keyNow); setVersion(v => v + 1) }

  useEffect(() => {
    const ac = new AbortController()
    setLoadError(null)
    roomApi.summary(rid, ac.signal)
      .then(setSummary)
      .catch(err => {
        if (ac.signal.aborted) return
        setLoadError(err instanceof RoomApiError && err.status === 404 ? 'gone' : 'network')
      })
    return () => ac.abort()
  }, [rid, version, attempt])
  useEffect(() => {
    if (loadError !== 'network' || attempt >= AUTO_RETRIES) return
    const t = setTimeout(() => setAttempt(n => n + 1), AUTO_RETRY_MS * (attempt + 1))
    return () => clearTimeout(t)
  }, [loadError, attempt])

  if (loadError === 'gone') return <Blocked reason="gone" />
  if (loadError === 'network') {
    return (
      <RoomPage label="우주">
        <header className={s.top}><Link to="/" aria-label="첫 화면"><Wordmark size={22} /></Link></header>
        <p className={s.error} role="alert">우주를 불러오지 못했어요. {attempt < AUTO_RETRIES ? '잠시 뒤 다시 불러올게요.' : '서버가 잠시 바쁠 수 있어요.'}</p>
        <button type="button" className={s.secondary} style={{ alignSelf: 'center', minWidth: 160 }} onClick={() => setAttempt(n => n + 1)}>다시 시도</button>
      </RoomPage>
    )
  }
  if (!summary) {
    return (
      <RoomPage label="우주 불러오는 중">
        <div className={s.bottom} style={{ margin: 'auto', alignItems: 'center', color: 'var(--r-text-2)' }} role="status"><Spinner size={28} /> 우주를 불러오는 중…</div>
      </RoomPage>
    )
  }
  if (summary.roomId !== rid) return null
  if (hasKey) return <RoomShell roomId={summary.roomId} onLostKey={() => changed(false)} />
  return <Entry key={version} summary={summary} onJoined={() => changed(true)} />
}

function Entry({ summary, onJoined }: { summary: RoomSummary; onJoined: () => void }) {
  const [picked, setView] = useState<View>({ kind: 'invite' })
  // 참가 화면은 최신 방 정보로 매번 판단한다 (불러오는 사이 사람이 다 찼거나 마감됐을 수 있음)
  const closedOrFull = summary.status === 'CLOSED' ? 'closed' : summary.members.length >= summary.capacity ? 'full' : null
  const view: View = picked.kind === 'invite' && closedOrFull ? { kind: 'blocked', reason: closedOrFull } : picked
  const toRejoin = (member: MemberView) => setView({ kind: 'rejoin', member })

  switch (view.kind) {
    case 'invite':
      return <Invite summary={summary} onJoined={onJoined} onPick={toRejoin} onBlocked={reason => setView({ kind: 'blocked', reason })} />
    case 'rejoin':
      return <Rejoin summary={summary} member={view.member} onJoined={onJoined} onBack={() => setView({ kind: 'invite' })} onForgot={() => setView({ kind: 'forgot', member: view.member })} />
    case 'forgot':
      return <Forgot summary={summary} member={view.member} onBack={() => toRejoin(view.member)} onHostReset={() => setView({ kind: 'hostReset' })} />
    case 'hostReset':
      return <HostReset summary={summary} onBack={() => setView({ kind: 'invite' })} onJoined={onJoined} />
    case 'blocked':
      return <Blocked reason={view.reason} summary={summary} onPick={toRejoin} />
  }
}

const hostOf = (s: RoomSummary) => s.members.find(m => m.role === 'HOST') ?? null

function RoomTag({ name }: { name: string }) {
  return <span className={s.tag}><NoteIcon size={15} /><span>{name}</span></span>
}

function MemberChips({ members, onPick }: { members: MemberView[]; onPick: (m: MemberView) => void }) {
  return (
    <div className={s.chips}>
      {members.map(m => (
        <button key={m.id} type="button" className={s.chip} onClick={() => onPick(m)}>
          <Avatar nickname={m.nickname} colorIdx={m.colorIdx} size={32} />
          {iAm(m.nickname)}
        </button>
      ))}
    </div>
  )
}

/** 5 초대받은 화면: 닉네임 + 4자리로 참가, 이미 참가했으면 내 이름 */
function Invite({ summary, onJoined, onPick, onBlocked }: {
  summary: RoomSummary; onJoined: () => void; onPick: (m: MemberView) => void; onBlocked: (r: 'full' | 'closed') => void
}) {
  const host = hostOf(summary)
  const [nickname, setNickname] = useState('')
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ field: 'nickname' | 'pin' | null; text: string } | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const nick = nickname.trim()
    if (nick.length < 1 || nick.length > 12) { setError({ field: 'nickname', text: '닉네임은 1~12자예요' }); return }
    if (!/^\d{4}$/.test(pin)) { setError({ field: 'pin', text: '숫자 4자리를 정해 주세요' }); return }
    setBusy(true); setError(null)
    try {
      const joined = await roomApi.join(summary.roomId, nick, pin)
      rememberJoin(joined, summary.name)
      onJoined()
    } catch (err) {
      if (err instanceof RoomApiError && err.code === 'ROOM_FULL') onBlocked('full')
      else if (err instanceof RoomApiError && err.code === 'ROOM_CLOSED') onBlocked('closed')
      else if (err instanceof RoomApiError && err.code === 'NICKNAME_TAKEN') setError({ field: 'nickname', text: '이 우주에 같은 닉네임이 있어요. 다른 이름으로 해 주세요' })
      else setError({ field: null, text: err instanceof RoomApiError ? err.message : '참가하지 못했어요. 잠시 뒤 다시 시도해 주세요.' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <RoomPage label="초대받은 우주">
      <header className={s.top}><Link to="/" aria-label="첫 화면"><Wordmark size={22} /></Link></header>

      <section className={s.section} style={{ alignItems: 'center', textAlign: 'center', gap: 12 }}>
        <div className={o.faces} aria-hidden="true">
          {summary.members.slice(0, 4).map(m => (
            <span key={m.id} className={o.face} style={{ background: memberColor(m.colorIdx).bg, color: memberColor(m.colorIdx).fg, ['--ring' as string]: memberColor(m.colorIdx).bg }}>{Array.from(m.nickname)[0] ?? ''}</span>
          ))}
          <span className={`${o.face} ${o.faceYou}`}>나</span>
        </div>
        <p className={o.eyebrow}>{host ? `${host.nickname}님의 우주에 초대받았어요` : '친구의 우주에 초대받았어요'}</p>
        <RoomTag name={summary.name} />
        <h1 className={s.title} style={{ fontSize: 24 }}>너도 좋아하는 노래로<br />같이 우주를 그려 볼래요?</h1>
        <p className={s.lead}>지금 {summary.members.length}명이 함께해요 · 최대 {summary.capacity}명</p>
      </section>

      <form className={`${s.card} ${o.glass}`} onSubmit={submit} noValidate>
        <div className={s.field}>
          <label htmlFor="inv-nick" className={s.label}>닉네임</label>
          <input id="inv-nick" className={s.input} maxLength={12} placeholder="방에서 보일 이름" value={nickname} autoComplete="nickname"
            aria-invalid={error?.field === 'nickname' || undefined} onChange={e => setNickname(e.target.value)} />
        </div>
        <div className={s.field}>
          <label htmlFor="inv-pin" className={s.label}>4자리 비밀번호</label>
          <CodeInput id="inv-pin" length={4} mask value={pin} onChange={setPin} label="4자리 비밀번호" invalid={error?.field === 'pin'} />
          <span className={s.hint}>다른 기기에서 다시 들어올 때 써요 · 다른 곳 비밀번호는 피해 주세요</span>
        </div>
        {error && <p className={s.error} role="alert">{error.text}</p>}
        <button type="submit" className={`${s.primary} ${o.cta}`} style={{ height: 54, fontSize: 16 }} disabled={busy}>
          {busy ? <><Spinner /> 들어가는 중…</> : '참가하고 노래 담기'}
        </button>
      </form>

      {summary.members.length > 0 && (
        <section className={s.section}>
          <span className={s.lead} style={{ fontSize: 13 }}>이미 참가했나요? 내 이름을 눌러 주세요</span>
          <MemberChips members={summary.members} onPick={onPick} />
        </section>
      )}

      <span className={`${s.hint} ${s.center}`} style={{ marginTop: 'auto' }}>가입 없음 · 닉네임과 노래만 저장해요</span>
    </RoomPage>
  )
}

/** 6-a 나는 OO예요 · 4자리 */
function Rejoin({ summary, member, onJoined, onBack, onForgot }: {
  summary: RoomSummary; member: MemberView; onJoined: () => void; onBack: () => void; onForgot: () => void
}) {
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lockedUntil, setLockedUntil] = useState<number | null>(null)
  const locked = lockedUntil !== null && lockedUntil > Date.now()

  async function submit(value = pin) {
    if (value.length !== 4 || busy || locked) return
    setBusy(true); setError(null)
    try {
      const joined = await roomApi.rejoin(summary.roomId, member.id, value)
      rememberJoin(joined, summary.name)
      onJoined()
    } catch (err) {
      setPin('')
      if (!(err instanceof RoomApiError)) setError('들어가지 못했어요. 잠시 뒤 다시 시도해 주세요.')
      else if (err.code === 'PIN_MISMATCH') {
        const n = err.num('attemptsLeft')
        setError(n !== null && n > 0 ? `비밀번호가 맞지 않아요 · ${n}번 더 시도할 수 있어요` : '비밀번호가 맞지 않아요')
      } else if (err.code === 'PIN_LOCKED') {
        const sec = err.num('retryAfterSeconds') ?? 600
        setLockedUntil(Date.now() + sec * 1000)
        setError(`5번 틀려서 잠시 잠겼어요 · ${minutesText(sec)} 뒤에 다시 해 주세요`)
      } else setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <RoomPage label="다시 들어오기">
      <header className={s.top}>
        <div className={s.topLeft}><BackButton onClick={onBack} /><span className={s.step}>다시 들어오기</span></div>
      </header>
      <section className={s.section}>
        <h1 className={s.title} style={{ fontSize: 24 }}>{member.nickname}님,<br />4자리 비밀번호를 입력해 주세요</h1>
        <p className={s.lead}>{member.role === 'HOST' ? '우주를 만들 때 정한 숫자예요.' : '처음 참가할 때 정한 숫자예요.'}</p>
      </section>
      <form className={s.section} style={{ alignItems: 'center', gap: 14 }} onSubmit={e => { e.preventDefault(); submit() }}>
        <Avatar nickname={member.nickname} colorIdx={member.colorIdx} size={64} />
        <div style={{ width: '100%' }}>
          <CodeInput length={4} mask value={pin} onChange={setPin} label={`${member.nickname}님의 4자리 비밀번호`} invalid={!!error} autoFocus onComplete={v => submit(v)} />
        </div>
        {error && <p className={s.error} role="alert" style={{ textAlign: 'center' }}>{error}</p>}
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </form>
      <div className={s.bottom}>
        <button type="button" className={`${s.primary} ${o.cta}`} style={{ height: 54, fontSize: 16 }} disabled={pin.length !== 4 || busy || locked} onClick={() => submit()}>
          {busy ? <><Spinner /> 확인하는 중…</> : '들어가기'}
        </button>
        <button type="button" className={s.ghost} onClick={onForgot}>비밀번호를 잊었어요</button>
        <span className={`${s.hint} ${s.center}`}>5번 틀리면 10분 뒤에 다시 할 수 있어요</span>
      </div>
    </RoomPage>
  )
}

/** 6-b 참가자 비밀번호를 잊었을 때 (방장이면 이메일 재설정 6-d로) */
function Forgot({ summary, member, onBack, onHostReset }: { summary: RoomSummary; member: MemberView; onBack: () => void; onHostReset: () => void }) {
  const host = hostOf(summary)
  const isHost = member.role === 'HOST'
  return (
    <RoomPage label="비밀번호를 잊었어요">
      <header className={s.top}>
        <div className={s.topLeft}><BackButton onClick={onBack} /><span className={s.step}>비밀번호를 잊었어요</span></div>
      </header>
      {isHost ? (
        <>
          <section className={s.section}>
            <h1 className={s.title} style={{ fontSize: 24 }}>등록한 이메일로<br />새 비밀번호를 정해요</h1>
            <p className={s.lead}>우주를 만들 때 인증한 이메일로 6자리 코드를 보내 드려요.</p>
          </section>
          <div className={s.bottom}>
            <button type="button" className={`${s.primary} ${o.cta}`} style={{ height: 54, fontSize: 16 }} onClick={onHostReset}>재설정 코드 받으러 가기</button>
          </div>
        </>
      ) : (
        <>
          <section className={s.section}>
            <h1 className={s.title} style={{ fontSize: 24 }}>방장 {host?.nickname ?? ''}님께<br />새 비밀번호를 부탁해 주세요</h1>
            <p className={s.lead}>{host?.nickname ?? '방장'}님이 4자리 새 비밀번호를 만들어 보내 줄 거예요. 받은 숫자로 바로 들어오면 돼요.</p>
          </section>
          <ol className={`${s.note} ${o.notice}`} style={{ margin: 0, paddingLeft: 34, fontSize: 14, lineHeight: 1.7, color: 'var(--r-silver)' }}>
            <li>카톡 등으로 {host?.nickname ?? '방장'}님께 "비밀번호 잊었어" 말하기</li>
            <li>{host?.nickname ?? '방장'}님이 보내 준 4자리 받기</li>
            <li>"{iAm(member.nickname)}"에서 그 숫자 입력</li>
          </ol>
          <div className={s.bottom}>
            <button type="button" className={`${s.primary} ${o.cta}`} style={{ height: 54, fontSize: 16 }} onClick={onBack}>새 비밀번호 받았어요</button>
          </div>
        </>
      )}
    </RoomPage>
  )
}

/** 6-g 들어갈 수 없는 우주: 꽉 참·마감·사라짐 */
function Blocked({ reason, summary, onPick }: { reason: 'full' | 'closed' | 'gone'; summary?: RoomSummary; onPick?: (m: MemberView) => void }) {
  const [showNames, setShowNames] = useState(false)
  const text = {
    full: { title: '이 우주는 자리가 다 찼어요', lead: `한 우주에는 ${summary?.capacity ?? 5}명까지 함께할 수 있어요.\n이미 참가했다면 내 이름으로 들어와 주세요.` },
    closed: { title: '방장이 새 참가를 닫았어요', lead: '이미 참가했다면 내 이름으로 들어와 주세요.' },
    gone: { title: '이 우주는 사라졌어요', lead: '링크가 틀렸거나 방장이 우주를 지웠어요.' },
  }[reason]

  return (
    <RoomPage label="들어갈 수 없는 우주">
      <header className={s.top}><Link to="/" aria-label="첫 화면"><Wordmark size={22} /></Link></header>
      <section className={s.section} style={{ margin: 'auto 0', alignItems: 'center', textAlign: 'center', gap: 14 }}>
        <svg width="96" height="96" viewBox="0 0 96 96" aria-hidden="true">
          <circle cx="48" cy="48" r="16" fill="#2E2A55" stroke="#B7AFD1" strokeWidth="1.4" />
          <circle cx="48" cy="48" r="34" fill="none" stroke="#3A3466" strokeWidth="1" />
          {reason !== 'gone' && <>
            <circle cx="82" cy="48" r="5" fill="#B9A2FF" /><circle cx="48" cy="14" r="5" fill="#E3A6B8" /><circle cx="20" cy="66" r="5" fill="#FFE3B8" />
            <circle cx="24" cy="26" r="5" fill="#B9A2FF" /><circle cx="70" cy="74" r="5" fill="#E3A6B8" />
          </>}
        </svg>
        {summary && <RoomTag name={summary.name} />}
        <h1 className={s.title} style={{ fontSize: 24 }}>{text.title}</h1>
        <p className={s.lead} style={{ whiteSpace: 'pre-line' }}>{text.lead}</p>

        <div className={s.section} style={{ width: '100%', marginTop: 12 }}>
          {reason !== 'gone' && summary && onPick && (
            showNames
              ? <MemberChips members={summary.members} onPick={onPick} />
              : <button type="button" className={s.secondary} onClick={() => setShowNames(true)}>이미 참가했어요</button>
          )}
          <Link to="/rooms/new" className={`${s.primary} ${o.cta}`} style={{ height: 54, fontSize: 16 }}>새 우주 만들기</Link>
        </div>
      </section>
    </RoomPage>
  )
}
