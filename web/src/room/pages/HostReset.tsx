import { useEffect, useState } from 'react'
import { rememberJoin, roomApi, RoomApiError, type RoomSummary } from '../roomApi'
import { BackButton, RoomPage, Spinner } from '../components/RoomPage'
import { CodeInput } from '../components/CodeInput'
import { hasBatchim, mmss } from '../text'
import s from '../room.module.css'
import o from '../components/outside.module.css'

/**
 * 6-d 방장 암호 재설정: 등록 이메일로 6자리 코드 → 코드 + 새 4자리 → 바로 방으로.
 * 이메일 주소는 화면에 보이지 않는다 (API도 돌려주지 않음, room-api-db.md 2절).
 */
export function HostReset({ summary, onBack, onJoined }: { summary: RoomSummary; onBack: () => void; onJoined: () => void }) {
  const [sent, setSent] = useState<{ codeExpiresAt: string; resendAfter: string } | null>(null)
  const [sending, setSending] = useState(false)
  const [code, setCode] = useState('')
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ field: 'code' | 'pin' | null; text: string } | null>(null)
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])

  async function send() {
    setSending(true); setError(null)
    try {
      setSent(await roomApi.hostReset(summary.roomId))
      setCode('')
    } catch (err) {
      setError({ field: null, text: err instanceof RoomApiError ? err.message : '코드를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.' })
    } finally {
      setSending(false)
    }
  }

  async function verify() {
    if (code.length !== 6) { setError({ field: 'code', text: '코드 6자리를 입력해 주세요' }); return }
    if (!/^\d{4}$/.test(pin)) { setError({ field: 'pin', text: '새 암호 숫자 4자리를 정해 주세요' }); return }
    setBusy(true); setError(null)
    try {
      const joined = await roomApi.hostResetVerify(summary.roomId, code, pin)
      rememberJoin(joined, summary.name)
      onJoined()
    } catch (err) {
      setCode('')
      if (!(err instanceof RoomApiError)) setError({ field: null, text: '확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.' })
      else if (err.code === 'CODE_MISMATCH') {
        const n = err.num('attemptsLeft')
        setError({ field: 'code', text: n !== null && n > 0 ? `코드가 맞지 않아요 · ${n}번 더 시도할 수 있어요` : '코드가 맞지 않아요. 코드를 다시 받아 주세요' })
      } else setError({ field: err.code === 'VALIDATION' && /암호/.test(err.message) ? 'pin' : 'code', text: err.message })
    } finally {
      setBusy(false)
    }
  }

  const left = sent ? (Date.parse(sent.codeExpiresAt) - now) / 1000 : 0
  const resendIn = sent ? (Date.parse(sent.resendAfter) - now) / 1000 : 0

  return (
    <RoomPage label="방장 암호 재설정">
      <header className={s.top}>
        <div className={s.topLeft}><BackButton onClick={onBack} /><span className={s.step}>방장 암호 재설정</span></div>
      </header>

      {!sent ? (
        <>
          <section className={s.section}>
            <h1 className={s.title} style={{ fontSize: 24 }}>등록한 이메일로<br />재설정 코드를 보내요</h1>
            <p className={s.lead}>'{summary.name}'{hasBatchim(summary.name) ? '을' : '를'} 만들 때 인증한 이메일로 6자리 코드를 보내 드려요. 10분 동안 쓸 수 있어요.</p>
          </section>
          {error && <p className={s.error} role="alert">{error.text}</p>}
          <div className={s.bottom}>
            <button type="button" className={`${s.primary} ${o.cta}`} style={{ height: 54, fontSize: 16 }} onClick={send} disabled={sending}>
              {sending ? <><Spinner /> 보내는 중…</> : '재설정 코드 받기'}
            </button>
          </div>
        </>
      ) : (
        <>
          <section className={s.section}>
            <h1 className={s.title} style={{ fontSize: 24 }}>등록한 이메일로<br />재설정 코드를 보냈어요</h1>
            <p className={s.lead}>메일에서 6자리 코드를 확인해 주세요. 10분 동안 쓸 수 있어요. 메일이 안 오면 스팸함도 확인해 주세요.</p>
          </section>

          <div className={s.field}>
            <label htmlFor="reset-code" className={s.label}>재설정 코드</label>
            <CodeInput id="reset-code" length={6} value={code} onChange={setCode} label="재설정 코드 6자리" invalid={error?.field === 'code'} autoFocus />
            <div className={s.top} style={{ minHeight: 0, fontSize: 13 }}>
              <span style={{ color: 'var(--r-text-2)' }}>{left > 0 ? <>남은 시간 <strong style={{ color: 'var(--r-text)', fontWeight: 500 }}>{mmss(left)}</strong></> : '코드가 만료됐어요 · 다시 받아 주세요'}</span>
              <button type="button" className={s.ghost} style={{ fontSize: 13, padding: '0 4px' }} disabled={resendIn > 0 || sending} onClick={send}>
                {sending ? '보내는 중…' : resendIn > 0 ? `다시 받기 · ${mmss(resendIn)} 뒤` : '코드 다시 받기'}
              </button>
            </div>
          </div>

          <div className={s.field}>
            <label htmlFor="reset-pin" className={s.label}>새 4자리 암호</label>
            <CodeInput id="reset-pin" length={4} mask value={pin} onChange={setPin} label="새 4자리 암호" invalid={error?.field === 'pin'} />
            <span className={s.hint}>은행·카드 비밀번호처럼 다른 곳에서 쓰는 숫자는 피해 주세요</span>
          </div>

          {error && <p className={s.error} role="alert">{error.text}</p>}

          <div className={s.bottom}>
            <button type="button" className={`${s.primary} ${o.cta}`} style={{ height: 54, fontSize: 16 }} onClick={verify} disabled={busy || left <= 0}>
              {busy ? <><Spinner /> 확인하는 중…</> : '새 암호로 들어가기'}
            </button>
          </div>
        </>
      )}
    </RoomPage>
  )
}
