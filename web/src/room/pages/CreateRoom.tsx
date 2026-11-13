import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { rememberJoin, roomApi, RoomApiError, type DraftResponse } from '../roomApi'
import { BackButton, RoomPage, Spinner } from '../components/RoomPage'
import { CodeInput } from '../components/CodeInput'
import { defaultRoomName, mmss } from '../text'
import s from '../room.module.css'

// 방 만들기: 2 내용 입력 → 메일 코드 → 3 코드 확인 → 방 생성 (방장 이메일 인증 순서 ⑥)
// 새로고침해도 코드 입력 단계가 이어지도록 진행 중인 초안 토큰은 sessionStorage에 둔다 (탭을 닫으면 사라짐)

const DRAFT_KEY = 'gyeol.draft.v1'
interface SavedDraft { draft: DraftResponse; roomName: string }

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/
const CODE_TTL_MS = 10 * 60_000
const RESEND_AFTER_MS = 60_000

function loadDraft(): SavedDraft | null {
  try {
    const raw = sessionStorage.getItem(DRAFT_KEY)
    const d = raw ? (JSON.parse(raw) as SavedDraft) : null
    return d && Date.parse(d.draft.expiresAt) > Date.now() ? d : null
  } catch {
    return null
  }
}
function saveDraft(d: SavedDraft | null) {
  try {
    if (d) sessionStorage.setItem(DRAFT_KEY, JSON.stringify(d))
    else sessionStorage.removeItem(DRAFT_KEY)
  } catch { /* 저장 실패는 무시 (새로고침하면 처음부터) */ }
}

export function CreateRoom() {
  const [saved, setSaved] = useState<SavedDraft | null>(loadDraft)
  const [form, setForm] = useState({ name: '', nickname: '', pin: '', email: '', consent: false })

  const toVerify = (d: SavedDraft) => { saveDraft(d); setSaved(d) }
  const backToForm = () => { saveDraft(null); setSaved(null) }

  return saved
    ? <VerifyStep saved={saved} onResent={toVerify} onRestart={backToForm} />
    : <FormStep form={form} setForm={setForm} onSent={toVerify} />
}

type Form = { name: string; nickname: string; pin: string; email: string; consent: boolean }

function FormStep({ form, setForm, onSent }: { form: Form; setForm: (f: Form) => void; onSent: (d: SavedDraft) => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [touched, setTouched] = useState(false)
  const set = <K extends keyof Form>(k: K, v: Form[K]) => setForm({ ...form, [k]: v })

  const nickname = form.nickname.trim()
  const problems = {
    nickname: nickname.length < 1 || nickname.length > 12 ? '닉네임은 1~12자예요' : null,
    pin: !/^\d{4}$/.test(form.pin) ? '숫자 4자리를 정해 주세요' : null,
    email: !EMAIL.test(form.email.trim()) ? '이메일 형식을 확인해 주세요' : null,
    consent: !form.consent ? '이메일 보관에 동의해야 우주를 만들 수 있어요' : null,
  }
  const firstProblem = Object.values(problems).find(Boolean) ?? null

  async function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (firstProblem) { setError(firstProblem); return }
    setBusy(true); setError(null)
    const roomName = form.name.trim() || defaultRoomName(nickname)
    try {
      const draft = await roomApi.createDraft({ name: roomName, nickname, pin: form.pin, email: form.email.trim(), consent: true })
      onSent({ draft, roomName })
    } catch (err) {
      setError(err instanceof RoomApiError ? err.message : '메일을 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.')
    } finally {
      setBusy(false)
    }
  }

  const invalid = (k: keyof typeof problems) => (touched && problems[k] ? true : undefined)

  return (
    <RoomPage label="우주 만들기">
      <header className={s.top}>
        <div className={s.topLeft}><BackButton to="/" /><span className={s.step}>1 / 2단계</span></div>
      </header>

      <section className={s.section} style={{ gap: 8 }}>
        <h1 className={s.title}>오늘 밤의 우주를 만들어요</h1>
        <p className={s.lead}>우주 이름과 내 이름, 다른 기기에서 들어올 때 쓸 암호를 정해 주세요.</p>
      </section>

      <form className={s.form} onSubmit={submit} noValidate id="create-room">
        <div className={s.field}>
          <label htmlFor="room-name" className={s.label}>우주 이름 <span className={s.optional}>· 선택</span></label>
          <input id="room-name" className={s.input} maxLength={20} placeholder="새벽 드라이브 플리" value={form.name} onChange={e => set('name', e.target.value)} />
          <span className={s.hint}>비워 두면 닉네임으로 지어 드려요 · 20자까지</span>
        </div>

        <div className={s.field}>
          <label htmlFor="room-nick" className={s.label}>닉네임</label>
          <input id="room-nick" className={s.input} maxLength={12} autoComplete="nickname" value={form.nickname} aria-invalid={invalid('nickname')} onChange={e => set('nickname', e.target.value)} />
          <span className={s.hint}>12자까지 · 방 안에서 겹치지 않게</span>
        </div>

        <div className={s.field}>
          <label htmlFor="room-pin" className={s.label}>4자리 암호</label>
          <CodeInput id="room-pin" length={4} mask value={form.pin} onChange={v => set('pin', v)} label="4자리 암호" invalid={!!invalid('pin')} />
          <span className={s.hint}>은행·카드 비밀번호처럼 다른 곳에서 쓰는 숫자는 피해 주세요</span>
        </div>

        <div className={s.field}>
          <label htmlFor="room-email" className={s.label}>이메일 <span className={s.optional}>· 방장 확인용</span></label>
          <input id="room-email" type="email" className={s.input} autoComplete="email" placeholder="name@example.com" value={form.email} aria-invalid={invalid('email')} onChange={e => set('email', e.target.value)} />
          <span className={s.hint}>암호를 잊었을 때 재설정 코드를 보내 드려요</span>
        </div>

        <label className={s.consent}>
          <input type="checkbox" checked={form.consent} onChange={e => set('consent', e.target.checked)} />
          <span>방 복구를 위해 이메일을 저장하는 데 동의해요. 방을 삭제할 때까지 보관해요.</span>
        </label>

        {error && <p className={s.error} role="alert">{error}</p>}
      </form>

      <div className={s.bottom}>
        <button type="submit" form="create-room" className={s.primary} disabled={busy}>
          {busy ? <><Spinner /> 메일 보내는 중…</> : '인증 메일 받기'}
        </button>
        <span className={`${s.hint} ${s.center}`}>친구들은 이메일 없이 닉네임만으로 들어와요</span>
      </div>
    </RoomPage>
  )
}

function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(t)
  }, [intervalMs])
  return now
}

function VerifyStep({ saved, onResent, onRestart }: { saved: SavedDraft; onResent: (d: SavedDraft) => void; onRestart: () => void }) {
  const navigate = useNavigate()
  const now = useNow()
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [resending, setResending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)

  const { draft } = saved
  // 코드 만료는 응답의 codeExpiresAt. 옛 응답(필드 없음)이면 보낸 시각(= 다시 받기 가능 시각 − 1분) + 10분으로 계산
  const codeExpiresAt = draft.codeExpiresAt
    ? Date.parse(draft.codeExpiresAt)
    : Math.min(Date.parse(draft.resendAfter) - RESEND_AFTER_MS + CODE_TTL_MS, Date.parse(draft.expiresAt))
  const left = (codeExpiresAt - now) / 1000
  const resendIn = (Date.parse(draft.resendAfter) - now) / 1000
  const expired = left <= 0

  async function verify(value = code) {
    if (value.length !== 6 || busy) return
    setBusy(true); setError(null); setInfo(null)
    try {
      const joined = await roomApi.verifyDraft(draft.draftToken, value)
      rememberJoin(joined, saved.roomName)
      saveDraft(null)
      navigate(`/rooms/${joined.roomId}`, { replace: true })
    } catch (err) {
      setCode('')
      if (!(err instanceof RoomApiError)) setError('확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.')
      else if (err.code === 'CODE_MISMATCH') {
        const n = err.num('attemptsLeft')
        setError(n !== null && n > 0 ? `코드가 맞지 않아요 · ${n}번 더 시도할 수 있어요` : '코드가 맞지 않아요. 코드를 다시 받아 주세요')
      } else if (err.code === 'DRAFT_NOT_FOUND') {
        onRestart()
      } else setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  async function resend() {
    setResending(true); setError(null); setInfo(null)
    try {
      const d = await roomApi.resendDraft(draft.draftToken)
      onResent({ ...saved, draft: d })
      setCode('')
      setInfo('새 코드를 보냈어요. 이전 코드는 쓸 수 없어요')
    } catch (err) {
      if (err instanceof RoomApiError && err.code === 'DRAFT_NOT_FOUND') onRestart()
      else setError(err instanceof RoomApiError ? err.message : '다시 보내지 못했어요.')
    } finally {
      setResending(false)
    }
  }

  return (
    <RoomPage label="이메일 인증">
      <header className={s.top}>
        <div className={s.topLeft}><BackButton onClick={onRestart} label="이메일 다시 쓰기" /><span className={s.step}>2 / 2단계</span></div>
      </header>

      <section className={s.section} style={{ gap: 14, alignItems: 'flex-start' }}>
        <span style={{ width: 56, height: 56, borderRadius: '50%', background: '#3A3366', color: 'var(--r-accent)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M3.5 7l8.5 6 8.5-6" /></svg>
        </span>
        <h1 className={s.title}>메일로 보낸<br />6자리 코드를 입력해 주세요</h1>
        <p className={s.lead}>
          <strong style={{ color: 'var(--r-text)', fontWeight: 500 }}>{draft.email}</strong> 으로 보냈어요.<br />
          10분 안에 입력하면 방이 만들어져요.
        </p>
      </section>

      <div className={s.field}>
        <label htmlFor="verify-code" className={s.label}>인증 코드</label>
        <CodeInput id="verify-code" length={6} value={code} onChange={setCode} label="인증 코드 6자리" invalid={!!error} autoFocus onComplete={v => verify(v)} />
        <div className={s.top} style={{ minHeight: 0, fontSize: 13 }}>
          <span style={{ color: 'var(--r-text-2)' }}>
            {expired ? '코드가 만료됐어요 · 다시 받아 주세요' : <>남은 시간 <strong style={{ color: 'var(--r-text)', fontWeight: 500 }}>{mmss(left)}</strong></>}
          </span>
          <button type="button" className={s.ghost} style={{ fontSize: 13, padding: '0 4px' }} disabled={resendIn > 0 || resending} onClick={resend}>
            {resending ? '보내는 중…' : resendIn > 0 ? `다시 받기 · ${mmss(resendIn)} 뒤` : '코드 다시 받기'}
          </button>
        </div>
        {error && <p className={s.error} role="alert">{error}</p>}
        {info && !error && <p className={s.hint} role="status">{info}</p>}
      </div>

      <section className={s.note}>
        <strong>메일이 안 왔나요?</strong>
        <span>스팸함을 확인해 주세요. 주소가 틀렸다면 <button type="button" className={s.linkButton} onClick={onRestart}>이메일 다시 쓰기</button></span>
      </section>

      <div className={s.bottom}>
        <button type="button" className={s.primary} disabled={code.length !== 6 || busy || expired} onClick={() => verify()}>
          {busy ? <><Spinner /> 확인하는 중…</> : '확인하고 방 만들기'}
        </button>
      </div>
    </RoomPage>
  )
}
