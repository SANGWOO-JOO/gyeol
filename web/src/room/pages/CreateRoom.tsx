import { useEffect, useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { rememberJoin, roomApi, RoomApiError, type DraftResponse } from '../roomApi'
import { BackButton, RoomPage, Spinner } from '../components/RoomPage'
import { CodeInput } from '../components/CodeInput'
import { defaultRoomName, mmss } from '../text'
import s from '../room.module.css'
import o from '../components/outside.module.css'
import c from './CreateRoom.module.css'

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
      <div className={c.screen}>
      <header className={c.top}>
        <BackButton to="/" />
        <div className={c.progress}>
          <span className={s.srOnly}>1 / 2단계</span>
          <svg width="76" height="24" viewBox="0 0 76 24" fill="none" aria-hidden="true">
            <path d="M23 12H55" stroke="#B9A2FF" strokeDasharray="2 4" />
            <circle cx="13" cy="12" r="8" fill="#E6DCFF" />
            <ellipse cx="13" cy="12" rx="12" ry="4" transform="rotate(-25 13 12)" stroke="#B9A2FF" />
            <circle cx="64" cy="12" r="6" fill="#29213F" stroke="#A69ABF" />
          </svg>
        </div>
      </header>

      <div className={c.hero}>
      <section className={c.intro}>
        <h1 className={s.title}>우주 만들기</h1>
      </section>

      <MyPlanet nickname={nickname} />
      </div>

      <form className={c.form} onSubmit={submit} noValidate id="create-room">
        <div className={c.card}>
          <div className={c.field}>
            <label htmlFor="room-nick" className={s.label}>친구들이 부를 이름</label>
            <input id="room-nick" className={`${s.input} ${c.input}`} maxLength={12} autoComplete="nickname" placeholder="별빛 수집가" value={form.nickname} aria-invalid={invalid('nickname')} onChange={e => set('nickname', e.target.value)} />
            <span className={c.hint}>12자까지 · 방 안에서 겹치지 않게 정해요</span>
          </div>
          <div className={c.field}>
            <label htmlFor="room-name" className={s.label}>우주 이름 <span className={c.optional}>· 선택</span></label>
            <input id="room-name" className={`${s.input} ${c.input}`} maxLength={20} placeholder="새벽 드라이브 플리" value={form.name} onChange={e => set('name', e.target.value)} />
            <span className={c.hint}>비워 두면 닉네임으로 지어 드려요 · 20자까지</span>
          </div>
        </div>

        <div className={c.card}>
          <div className={`${c.field} ${c.pin}`}>
            <label htmlFor="room-pin" className={s.label}>비밀번호 4자리</label>
            <CodeInput id="room-pin" length={4} mask value={form.pin} onChange={v => set('pin', v)} label="비밀번호 4자리" invalid={!!invalid('pin')} />
            <span className={c.hint}>은행·카드 등 다른 곳에서 쓰는 숫자는 피해 주세요</span>
          </div>
          <div className={c.field}>
            <label htmlFor="room-email" className={s.label}>이메일 <span className={c.optional}>· 내가 방장인 걸 확인할 때</span></label>
            <input id="room-email" type="email" className={`${s.input} ${c.input}`} autoComplete="email" placeholder="name@example.com" value={form.email} aria-invalid={invalid('email')} onChange={e => set('email', e.target.value)} />
            <span className={c.hint}>비밀번호를 잊으면 재설정 코드를 보내 드려요</span>
          </div>
        </div>

        <label className={`${s.consent} ${c.consent}`}>
          <input type="checkbox" checked={form.consent} onChange={e => set('consent', e.target.checked)} />
          <span>방 복구용 이메일을 방 삭제 전까지 보관하는 데 동의해요. <a href="/privacy" target="_blank" rel="noopener" className={o.consentLink}>개인정보 처리방침</a></span>
        </label>

        {error && <p className={s.error} role="alert">{error}</p>}
      </form>

      <div className={c.bottom}>
        <button type="submit" form="create-room" className={`${s.primary} ${o.cta} ${c.cta}`} disabled={busy}>
          {busy ? <><Spinner /> 메일 보내는 중…</> : '인증 메일 받기'}
        </button>
        <span className={c.hint}>친구들은 이메일 없이 닉네임만으로 들어와요</span>
      </div>
      </div>
    </RoomPage>
  )
}

/** Landing의 라벤더 행성에 이름을 붙이는 작은 미리보기. */
function MyPlanet({ nickname }: { nickname: string }) {
  const prefix = `cr-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  return (
    <div className={c.preview}>
      <svg viewBox="0 0 300 100" fill="none" aria-hidden="true" className={c.planet}>
        <defs>
          <linearGradient id={`${prefix}-planet`} x1="120" y1="15" x2="178" y2="85" gradientUnits="userSpaceOnUse"><stop stopColor="#E6DCFF" /><stop offset="1" stopColor="#A28AD9" /></linearGradient>
          <radialGradient id={`${prefix}-haze`}><stop stopColor="#B9A2FF" stopOpacity=".2" /><stop offset="1" stopColor="#B9A2FF" stopOpacity="0" /></radialGradient>
        </defs>
        <ellipse cx="150" cy="53" rx="100" ry="48" fill={`url(#${prefix}-haze)`} />
        <path d="M60 72Q145 106 242 34" stroke="#E6DCFF" strokeOpacity=".25" strokeDasharray="2 5" />
        <g className={c.float}>
          <ellipse cx="150" cy="50" rx="62" ry="16" transform="rotate(-22 150 50)" stroke="#B9A2FF" strokeOpacity=".6" />
          <path d="M110 48C108 24 126 10 150 12C174 10 192 28 190 51C191 74 173 89 149 87C126 89 108 71 110 48Z" fill={`url(#${prefix}-planet)`} stroke="#E6DCFF" strokeOpacity=".65" />
          <path d="M121 35Q129 21 144 23" stroke="#F5EEFF" strokeWidth="2" strokeLinecap="round" strokeOpacity=".65" />
          <g stroke="#665185" strokeWidth="1.8" strokeLinecap="round">
            {nickname ? <path d="M137 45Q140 40 143 45M158 45Q161 40 164 45M145 55Q151 63 157 55" /> : <path d="M136 46Q140 49 144 46M157 46Q161 49 165 46M148 57H154" />}
          </g>
          <g fill="#E3A6B8" opacity={nickname ? '.8' : '.45'}><ellipse cx="131" cy="54" rx="6" ry="3" /><ellipse cx="171" cy="54" rx="6" ry="3" /></g>
          <path d="M94 67C106 85 182 60 206 32" stroke="#C9B5FF" strokeWidth="4" strokeLinecap="round" />
          <path d="M97 68C116 78 177 59 201 37" stroke="#F0E7FF" strokeWidth="1.2" strokeLinecap="round" />
        </g>
        <g stroke="#FFE3B8" strokeWidth="1.6" strokeLinecap="round" className={c.notes}><path d="M76 39V26L85 24V35" /><ellipse cx="73" cy="40" rx="3" ry="2" fill="#FFE3B8" /><ellipse cx="82" cy="36" rx="3" ry="2" fill="#FFE3B8" /></g>
        <path d="M225 12C213 15 212 28 223 32C229 34 235 30 236 26C225 31 218 20 225 12Z" fill="#FFE3B8" opacity=".8" />
        <g key={nickname ? 'awake' : 'sleepy'} className={nickname ? c.sparkles : c.sleepy} fill="#FFE3B8">
          <path d="M211 64Q212 71 219 72Q212 73 211 80Q210 73 203 72Q210 71 211 64ZM99 13Q100 18 105 19Q100 20 99 25Q98 20 93 19Q98 18 99 13Z" />
          <circle cx="65" cy="66" r="1.5" /><circle cx="244" cy="52" r="1.5" />
        </g>
      </svg>
      <span className={`${c.nameTag} ${nickname ? c.named : ''}`}>{nickname || '아직 이름 없는 내 행성'}</span>
    </div>
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

      <section className={`${s.note} ${o.notice}`}>
        <strong>메일이 안 왔나요?</strong>
        <span>스팸함을 확인해 주세요. 주소가 틀렸다면 <button type="button" className={s.linkButton} onClick={onRestart}>이메일 다시 쓰기</button></span>
      </section>

      <div className={s.bottom}>
        <button type="button" className={`${s.primary} ${o.cta}`} disabled={code.length !== 6 || busy || expired} onClick={() => verify()}>
          {busy ? <><Spinner /> 확인하는 중…</> : '확인하고 방 만들기'}
        </button>
      </div>
    </RoomPage>
  )
}
