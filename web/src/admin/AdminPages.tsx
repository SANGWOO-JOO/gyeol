import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link, NavLink, useNavigate, useParams } from 'react-router'
import { useRoomTheme, Spinner } from '../room/components/RoomPage'
import { Wordmark } from '../room/components/Wordmark'
import { CodeInput } from '../room/components/CodeInput'
import { adminApi, AdminApiError, type AdminMe, type AdminRoomDetail, type AdminRoomRow, type AdminSort, type AuditView } from './adminApi'
import { ago, auditLine, shortTime, statusLabel } from './adminText'
import a from './admin.module.css'
import s from '../room/room.module.css'

// 관리자 화면 (room-web.md 8단계, 캔버스 7-a~7-c). 세션 쿠키가 없거나 만료되면(401) 로그인으로 보낸다.

const errText = (e: unknown, fb: string) => (e instanceof AdminApiError ? e.message : fb)

/** 로그인한 관리자만: 왼쪽 메뉴·세션 남은 시간·로그아웃 */
function AdminFrame({ children }: { children: (me: AdminMe, onAuthError: (e: unknown) => boolean) => ReactNode }) {
  useRoomTheme()
  const navigate = useNavigate()
  const [me, setMe] = useState<AdminMe | null>(null)
  const [now, setNow] = useState(() => Date.now())

  const onAuthError = useCallback((e: unknown) => {
    if (e instanceof AdminApiError && e.status === 401) { navigate('/admin', { replace: true }); return true }
    return false
  }, [navigate])

  useEffect(() => {
    const ac = new AbortController()
    adminApi.me(ac.signal).then(setMe).catch(e => { if (!ac.signal.aborted) onAuthError(e) })
    return () => ac.abort()
  }, [onAuthError])
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 30_000); return () => clearInterval(t) }, [])

  async function logout() {
    try { await adminApi.logout() } catch { /* 이미 만료됐어도 로그인으로 */ }
    navigate('/admin', { replace: true })
  }

  if (!me) return <div className={a.loginWrap} role="status"><Spinner size={24} /></div>
  const left = Math.max(0, Math.round((Date.parse(me.sessionExpiresAt) - now) / 60000))
  return (
    <div className={a.app}>
      <aside className={a.side}>
        <div className={a.brand}><Wordmark size={22} /><span className={a.adminTag}>관리자</span></div>
        <nav aria-label="관리 메뉴" className={a.nav}>
          <NavLink to="/admin/rooms" className={a.navItem}>우주</NavLink>
          <NavLink to="/admin/audit" className={a.navItem}>감사 기록</NavLink>
        </nav>
        <div className={a.sideFoot}>
          {me.email.replace(/@.*/, '@')} · 세션 {left}분 남음<br />
          <button type="button" className={a.linkish} onClick={logout}>로그아웃</button>
        </div>
      </aside>
      <main className={a.main}>{children(me, onAuthError)}</main>
    </div>
  )
}

/** 7-a 관리자 로그인: 이메일·비밀번호 → OTP 6자리 */
export function AdminLogin() {
  useRoomTheme()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [challenge, setChallenge] = useState<string | null>(null)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 이미 로그인돼 있으면 목록으로
  useEffect(() => {
    const ac = new AbortController()
    adminApi.me(ac.signal).then(() => navigate('/admin/rooms', { replace: true })).catch(() => { /* 로그인 화면 그대로 */ })
    return () => ac.abort()
  }, [navigate])

  async function step1(e: FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null)
    try {
      const r = await adminApi.login(email.trim(), password)
      setChallenge(r.challenge); setPassword('')
    } catch (err) {
      setError(errText(err, '로그인하지 못했어요'))
    } finally {
      setBusy(false)
    }
  }

  async function step2(value = code) {
    if (!challenge || value.length !== 6) return
    setBusy(true); setError(null)
    try {
      await adminApi.otp(challenge, value)
      navigate('/admin/rooms', { replace: true })
    } catch (err) {
      setCode('')
      setError(errText(err, '인증하지 못했어요'))
      // 인증 단계가 만료되면 처음부터
      if (err instanceof AdminApiError && err.status === 401 && /만료|다시/.test(err.message)) setChallenge(null)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={a.loginWrap}>
      <form className={a.loginCard} onSubmit={challenge ? e => { e.preventDefault(); step2() } : step1} noValidate>
        <div className={a.brand}><Wordmark size={22} /><span className={a.adminTag}>관리자</span></div>
        <h1 className={a.h1} style={{ fontSize: 22 }}>관리자 로그인</h1>
        {!challenge ? (
          <>
            <div className={s.field}>
              <label htmlFor="ad-email" className={s.label}>이메일</label>
              <input id="ad-email" type="email" autoComplete="username" placeholder="관리자 이메일" value={email} onChange={e => setEmail(e.target.value)} />
            </div>
            <div className={s.field}>
              <label htmlFor="ad-pw" className={s.label}>비밀번호</label>
              <input id="ad-pw" type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} />
            </div>
          </>
        ) : (
          <div className={s.field}>
            <label htmlFor="ad-otp" className={s.label}>2단계 인증 코드 <span className={s.optional}>· OTP 앱의 6자리</span></label>
            <CodeInput id="ad-otp" length={6} value={code} onChange={setCode} label="OTP 6자리" invalid={!!error} autoFocus onComplete={v => step2(v)} />
          </div>
        )}
        {error && <p className={a.error} role="alert">{error}</p>}
        <button type="submit" className={s.primary} style={{ height: 52, fontSize: 16 }} disabled={busy || (!challenge ? !email || !password : code.length !== 6)}>
          {busy ? <Spinner /> : challenge ? '확인' : '다음'}
        </button>
        {challenge && <button type="button" className={s.ghost} onClick={() => { setChallenge(null); setCode(''); setError(null) }}>처음부터 다시</button>}
        <span className={a.small} style={{ lineHeight: 1.6 }}>허용된 IP에서만 열리는 페이지예요 · 5번 틀리면 15분 잠겨요 · 세션은 1시간</span>
      </form>
    </div>
  )
}

/** 7-b 우주 목록: 검색·상태·정렬·쪽 */
export function AdminRooms() {
  return <AdminFrame>{(_me, onAuthError) => <RoomsBody onAuthError={onAuthError} />}</AdminFrame>
}

const PAGE = 30
function RoomsBody({ onAuthError }: { onAuthError: (e: unknown) => boolean }) {
  const [q, setQ] = useState('')
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<'' | 'HIDDEN' | 'CLOSED' | 'ACTIVE'>('')
  const [sort, setSort] = useState<AdminSort>('created')
  const [page, setPage] = useState(0)
  const [rows, setRows] = useState<AdminRoomRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { const t = setTimeout(() => { setQuery(q.trim()); setPage(0) }, 300); return () => clearTimeout(t) }, [q])
  useEffect(() => {
    const ac = new AbortController()
    setRows(null); setError(null)
    adminApi.rooms({ q: query || undefined, status: status || undefined, sort, page, size: PAGE }, ac.signal)
      .then(r => setRows(r.items))
      .catch(e => { if (!ac.signal.aborted && !onAuthError(e)) setError(errText(e, '목록을 불러오지 못했어요')) })
    return () => ac.abort()
  }, [query, status, sort, page, onAuthError])

  const now = new Date()
  return (
    <>
      <div className={a.toolbar}>
        <h1 className={a.h1}>우주</h1>
        <div className={a.filters}>
          <input className={a.search} aria-label="우주 이름이나 방장 닉네임 검색" placeholder="우주 이름, 방장 닉네임" value={q} onChange={e => setQ(e.target.value)} />
          <div role="group" aria-label="상태" className={a.filters}>
            {([['', '전체'], ['ACTIVE', '활성'], ['CLOSED', '마감'], ['HIDDEN', '숨김만']] as const).map(([v, label]) => (
              <button key={v || 'all'} type="button" className={a.seg} aria-pressed={status === v} onClick={() => { setStatus(v); setPage(0) }}>{label}</button>
            ))}
          </div>
          <label className={a.small} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            정렬
            <select className={a.select} value={sort} onChange={e => { setSort(e.target.value as AdminSort); setPage(0) }}>
              <option value="created">만든 날 최근 순</option>
              <option value="active">오래 활동 없는 순</option>
              <option value="songs">노래 많은 순</option>
            </select>
          </label>
        </div>
      </div>

      {error && <p className={a.error} role="alert">{error}</p>}
      <div className={a.tableWrap}>
        <table className={a.table}>
          <thead><tr><th>우주 이름</th><th>방장</th><th>참가자</th><th>노래</th><th>만든 날</th><th>마지막 활동</th><th>상태</th></tr></thead>
          <tbody>
            {rows === null && !error && <tr><td colSpan={7} className={a.muted} role="status"><Spinner size={14} /> 불러오는 중…</td></tr>}
            {rows?.length === 0 && <tr><td colSpan={7} className={a.muted}>조건에 맞는 우주가 없어요</td></tr>}
            {rows?.map(r => {
              const st = statusLabel(r, 5, now)
              return (
                <tr key={r.publicId}>
                  <td><Link to={`/admin/rooms/${r.publicId}`}>{r.name}</Link></td>
                  <td>{r.hostNickname ?? '-'}</td>
                  <td>{r.memberCount} / 5</td>
                  <td>{r.songCount}</td>
                  <td className={a.muted}>{shortTime(r.createdAt, now).slice(0, 5)}</td>
                  <td className={a.muted}>{ago(r.lastActiveAt, now)}</td>
                  <td><span className={`${a.pill} ${a[`pill_${st.tone}`]}`}>{st.text}</span></td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className={a.pager}>
        <button type="button" className={a.btn} disabled={page === 0} onClick={() => setPage(p => p - 1)}>이전</button>
        <span>{page + 1}쪽</span>
        <button type="button" className={a.btn} disabled={!rows || rows.length < PAGE} onClick={() => setPage(p => p + 1)}>다음</button>
      </div>
      <span className={a.small}>"오래 활동 없는 순"으로 정렬하면 정리할 우주를 찾기 쉬워요</span>
    </>
  )
}

/** 7-c 우주 상세: 숨김·되살리기·이름 변경, 참가자 닉네임 변경·내보내기, 이 우주의 감사 기록 */
export function AdminRoom() {
  return <AdminFrame>{(_me, onAuthError) => <RoomBody onAuthError={onAuthError} />}</AdminFrame>
}

function RoomBody({ onAuthError }: { onAuthError: (e: unknown) => boolean }) {
  const { rid = '' } = useParams()
  const [d, setD] = useState<AdminRoomDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [renaming, setRenaming] = useState<string | null>(null)
  const [memberEdit, setMemberEdit] = useState<{ id: number; value: string } | null>(null)
  const [confirm, setConfirm] = useState<{ kind: 'hide' | 'kick'; memberId?: number; reason: string } | null>(null)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    const ac = new AbortController()
    adminApi.room(rid, ac.signal).then(setD).catch(e => { if (!ac.signal.aborted && !onAuthError(e)) setError(errText(e, '우주를 불러오지 못했어요')) })
    return () => ac.abort()
  }, [rid, reload, onAuthError])

  async function act(key: string, fn: () => Promise<void>, done: string) {
    setBusy(key); setError(null); setNotice(null)
    try { await fn(); setNotice(done); setReload(n => n + 1); setConfirm(null); setRenaming(null); setMemberEdit(null) }
    catch (e) { if (!onAuthError(e)) setError(errText(e, '처리하지 못했어요')) }
    finally { setBusy(null) }
  }

  if (!d) return error ? <p className={a.error} role="alert">{error}</p> : <p className={a.small} role="status"><Spinner size={14} /> 불러오는 중…</p>
  const r = d.room
  const st = statusLabel(r)
  const now = new Date()
  const active = d.members.filter(m => !m.removedAt)

  return (
    <>
      <Link to="/admin/rooms" className={a.small} style={{ fontSize: 14, color: 'var(--r-text-2)', textDecoration: 'none' }}>‹ 우주 목록</Link>
      <div className={a.toolbar} style={{ alignItems: 'center' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <h1 className={a.h1}>{r.name}</h1>
          <span className={`${a.pill} ${a[`pill_${st.tone}`]}`}>{st.text}</span>
        </div>
        <div className={a.filters}>
          {renaming === null
            ? <button type="button" className={`${a.btn} ${a.big}`} onClick={() => setRenaming(r.name)} disabled={r.status === 'HIDDEN'}>이름 바꾸기</button>
            : (
              <form className={a.inlineEdit} style={{ width: 'auto' }} onSubmit={e => { e.preventDefault(); act('rename', () => adminApi.renameRoom(rid, renaming.trim()), '우주 이름을 바꿨어요') }}>
                <input aria-label="새 우주 이름" value={renaming} maxLength={30} onChange={e => setRenaming(e.target.value)} />
                <button type="submit" className={`${a.btnPrimary} ${a.big}`} disabled={busy !== null || !renaming.trim()}>저장</button>
                <button type="button" className={`${a.btn} ${a.big}`} onClick={() => setRenaming(null)}>취소</button>
              </form>
            )}
          {r.status === 'HIDDEN'
            ? <button type="button" className={`${a.btnPrimary} ${a.big}`} disabled={busy !== null} onClick={() => act('restore', () => adminApi.restore(rid), '우주를 되살렸어요')}>되살리기</button>
            : <button type="button" className={`${a.btnDanger} ${a.big}`} onClick={() => setConfirm({ kind: 'hide', reason: '' })}>숨기기 (7일 뒤 완전 삭제)</button>}
        </div>
      </div>

      {confirm && (
        <form className={a.notice} style={{ display: 'flex', flexDirection: 'column', gap: 10 }} role="group" aria-label="확인"
          onSubmit={e => {
            e.preventDefault()
            if (confirm.kind === 'hide') act('hide', () => adminApi.hide(rid, confirm.reason.trim()), '숨겼어요 · 7일 뒤 완전히 지워져요')
            else act('kick', () => adminApi.kick(rid, confirm.memberId!, confirm.reason.trim()), '참가자를 내보냈어요')
          }}>
          <strong>{confirm.kind === 'hide' ? '이 우주를 숨길까요? 참가자 모두 들어올 수 없게 되고, 7일 뒤 완전히 지워져요.' : `${active.find(m => m.id === confirm.memberId)?.nickname}님을 내보낼까요? 담은 노래도 함께 지워져요.`}</strong>
          <div className={a.inlineEdit}>
            <input aria-label="사유 (감사 기록에 남아요)" placeholder="사유 (감사 기록에 남아요)" value={confirm.reason} onChange={e => setConfirm({ ...confirm, reason: e.target.value })} />
            <button type="submit" className={`${a.btnDanger} ${a.big}`} disabled={busy !== null}>{busy ? <Spinner size={14} /> : confirm.kind === 'hide' ? '숨기기' : '내보내기'}</button>
            <button type="button" className={`${a.btn} ${a.big}`} onClick={() => setConfirm(null)}>취소</button>
          </div>
        </form>
      )}
      {notice && <p className={a.notice} role="status">{notice}</p>}
      {error && <p className={a.error} role="alert">{error}</p>}

      <div className={a.cards}>
        <div className={a.card}><span>방장</span><div>{r.hostNickname ?? '-'} · {d.hostEmail ?? '이메일 없음'}</div></div>
        <div className={a.card}><span>참가자 · 노래</span><div>{active.length} / 5명 · {r.songCount}곡</div></div>
        <div className={a.card}><span>만든 날 · 마지막 활동</span><div>{shortTime(r.createdAt, now).slice(0, 5)} · {ago(r.lastActiveAt, now)}</div></div>
        <div className={a.card}><span>새 참가</span><div>{r.status === 'CLOSED' ? '마감' : r.status === 'HIDDEN' ? '숨김' : '열림'}</div></div>
      </div>

      <div className={a.cols}>
        <section className={a.panel} aria-label="참가자">
          <h2 className={a.h2}>참가자</h2>
          {d.members.map(m => (
            <div key={m.id} className={a.memberRow}>
              <span className={s.avatar} aria-hidden="true" style={{ width: 32, height: 32, background: 'var(--r-chip)', fontSize: 13 }}>{Array.from(m.nickname)[0]}</span>
              <span>{m.nickname} <span className={a.small}>{m.role === 'HOST' ? '방장 · ' : ''}노래 {m.songCount}곡{m.removedAt ? ' · 나감' : ''}</span></span>
              {!m.removedAt && (memberEdit?.id === m.id ? (
                <form className={a.inlineEdit} onSubmit={e => { e.preventDefault(); act(`m${m.id}`, () => adminApi.renameMember(rid, m.id, memberEdit.value.trim()), '닉네임을 바꿨어요') }}>
                  <input aria-label={`${m.nickname}님의 새 닉네임`} value={memberEdit.value} maxLength={12} onChange={e => setMemberEdit({ id: m.id, value: e.target.value })} />
                  <button type="submit" className={a.btnPrimary} disabled={busy !== null || !memberEdit.value.trim()}>저장</button>
                  <button type="button" className={a.btn} onClick={() => setMemberEdit(null)}>취소</button>
                </form>
              ) : (
                <>
                  <button type="button" className={a.btn} onClick={() => setMemberEdit({ id: m.id, value: m.nickname })}>닉네임 바꾸기</button>
                  {m.role !== 'HOST' && <button type="button" className={a.btnDanger} onClick={() => setConfirm({ kind: 'kick', memberId: m.id, reason: '' })}>내보내기</button>}
                </>
              ))}
            </div>
          ))}
        </section>
        <section className={a.panel} style={{ flexBasis: 360 }} aria-label="감사 기록">
          <h2 className={a.h2}>감사 기록</h2>
          <AuditList items={d.audit} />
        </section>
      </div>
      <span className={a.small}>관리자는 노래 목록과 방장 이메일 전체를 보지 않아요 · 필요한 정보만 가려서 보여 줘요</span>
    </>
  )
}

function AuditList({ items }: { items: AuditView[] }) {
  const now = new Date()
  if (items.length === 0) return <span className={a.small}>기록이 없어요</span>
  return (
    <ul className={a.auditList}>
      {items.map(x => { const l = auditLine(x, now); return <li key={x.id}><time dateTime={x.createdAt}>{l.when}</time><span>{l.text}</span></li> })}
    </ul>
  )
}

/** 감사 기록 전체 (사이드 메뉴) */
export function AdminAudit() {
  return <AdminFrame>{(_me, onAuthError) => <AuditBody onAuthError={onAuthError} />}</AdminFrame>
}

function AuditBody({ onAuthError }: { onAuthError: (e: unknown) => boolean }) {
  const [page, setPage] = useState(0)
  const [items, setItems] = useState<AuditView[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const ac = new AbortController()
    setItems(null)
    adminApi.audit({ page, size: 50 }, ac.signal).then(setItems).catch(e => { if (!ac.signal.aborted && !onAuthError(e)) setError(errText(e, '불러오지 못했어요')) })
    return () => ac.abort()
  }, [page, onAuthError])
  return (
    <>
      <h1 className={a.h1}>감사 기록</h1>
      {error && <p className={a.error} role="alert">{error}</p>}
      <section className={a.panel}>
        {items === null && !error ? <span className={a.small} role="status"><Spinner size={14} /> 불러오는 중…</span> : items && <AuditList items={items} />}
      </section>
      <div className={a.pager}>
        <button type="button" className={a.btn} disabled={page === 0} onClick={() => setPage(p => p - 1)}>이전</button>
        <span>{page + 1}쪽</span>
        <button type="button" className={a.btn} disabled={!items || items.length < 50} onClick={() => setPage(p => p + 1)}>다음</button>
      </div>
      <span className={a.small}>1년 동안 보관해요 · 지워진 우주의 기록은 IP와 닉네임을 지운 채 남아요</span>
    </>
  )
}
