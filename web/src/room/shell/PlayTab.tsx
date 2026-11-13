import { useEffect, useState } from 'react'
import { roomApi, RoomApiError, type CodeMatchView, type MemberRow } from '../roomApi'
import { codeMatchDisplay, sourceName, unlockText } from '../roomLogic'
import { Avatar } from '../components/Avatar'
import { Spinner } from '../components/RoomPage'
import type { ToastData } from './Toast'
import s from '../room.module.css'
import sh from './shell.module.css'

const MIN_SONGS = 5

/**
 * 놀이터 탭 (room-plan ⑰). 첫 배포는 "코드 맞추기" 하나: 8-a 두 사람 고르기 → 8-b 결과.
 * 점수는 서버가 바로 계산하고(LLM 없음), 이유 문장은 worker가 뒤에서 쓴다(REASONING → DONE/NO_REASON).
 */
export function PlayTab({ roomId, meId, version, onError, show }: {
  roomId: string; meId: number; version: number; onError: (e: unknown) => boolean; show: (t: Omit<ToastData, 'id'>) => void
}) {
  const [rows, setRows] = useState<MemberRow[] | null>(null)
  const [picked, setPicked] = useState<number[]>([])
  const [result, setResult] = useState<CodeMatchView | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const ac = new AbortController()
    roomApi.members(roomId, ac.signal)
      .then(r => {
        setRows(r)
        // 처음에는 나를 골라 둔다 (5곡 이상일 때)
        setPicked(prev => prev.length > 0 ? prev.filter(id => r.some(m => m.id === id && m.songCount >= MIN_SONGS))
          : r.some(m => m.id === meId && m.songCount >= MIN_SONGS) ? [meId] : [])
      })
      .catch(err => { if (!ac.signal.aborted && !onError(err)) setRows([]) })
    return () => ac.abort()
  }, [roomId, meId, version, onError])

  function toggle(id: number) {
    setPicked(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id].slice(-2))
  }

  async function run(a: number, b: number) {
    setBusy(true)
    try {
      setResult(await roomApi.codeMatch(roomId, a, b))
    } catch (err) {
      if (onError(err)) return
      const text = err instanceof RoomApiError ? err.message : '코드를 맞추지 못했어요'
      show({ title: text, tone: 'error' })
    } finally {
      setBusy(false)
    }
  }

  if (result) return <CodeResult roomId={roomId} initial={result} onBack={() => setResult(null)} onRerun={() => run(result.a.memberId, result.b.memberId)} busy={busy} onError={onError} />

  const names = picked.map(id => rows?.find(m => m.id === id)?.nickname).filter(Boolean)
  return (
    <section aria-label="놀이터" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <h2 className={sh.playTitle}>놀이터</h2>

      <section aria-label="코드 맞추기" className={sh.playCard}>
        <div className={sh.playHead}>
          <span className={sh.playIcon} aria-hidden="true">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M9 18V6l10-2v12" /><circle cx="6.5" cy="18" r="2.5" /><circle cx="16.5" cy="16" r="2.5" /></svg>
          </span>
          <span className={sh.personText}>
            <strong style={{ fontSize: 18 }}>코드 맞추기</strong>
            <span style={{ fontSize: 13, color: 'var(--r-accent-strong)' }}>친구랑 음악 코드가 얼마나 맞는지 봐요</span>
          </span>
        </div>
        <span style={{ fontSize: 13, fontWeight: 500 }}>누구랑 맞춰 볼까요? <span style={{ fontWeight: 400, color: 'var(--r-text-3)' }}>· 두 명</span></span>

        {rows === null && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
        {rows && (
          <ul className={sh.list} role="group" aria-label="두 사람 고르기">
            {rows.map(m => {
              const locked = unlockText(m.songCount, MIN_SONGS)
              const on = picked.includes(m.id)
              return (
                <li key={m.id}>
                  <button type="button" className={sh.pickRow} aria-pressed={on} disabled={!!locked} onClick={() => toggle(m.id)}>
                    <Avatar nickname={m.nickname} colorIdx={m.colorIdx} characterId={m.avatarCharacterId} size={38} />
                    <span className={sh.personText}>
                      <span style={{ fontSize: 15 }}>{m.nickname}{m.me ? ' · 나' : ''}</span>
                      <span className={sh.songSub}>노래 {m.songCount}곡{locked ? ` · ${locked}` : ''}</span>
                    </span>
                    <span className={sh.check} aria-hidden="true">{on ? '✓' : ''}</span>
                  </button>
                </li>
              )
            })}
          </ul>
        )}

        <button type="button" className={s.primary} style={{ height: 52, fontSize: 16 }} disabled={picked.length !== 2 || busy}
          onClick={() => run(picked[0], picked[1])}>
          {busy ? <Spinner /> : picked.length === 2 ? `${names[0]} × ${names[1]} 코드 맞추기` : '두 사람을 골라 주세요'}
        </button>
        <span className={sh.center}>각자 노래 {MIN_SONGS}곡부터 할 수 있어요</span>
      </section>

      <section aria-label="준비 중" className={sh.soonCard}>
        <span aria-hidden="true" style={{ fontSize: 18 }}>✦</span>
        <span className={sh.personText}>
          <span style={{ fontSize: 14, color: 'var(--r-silver)' }}>취향 월드컵</span>
          <span style={{ fontSize: 12 }}>우리 우주 노래끼리 대결 · 준비 중</span>
        </span>
      </section>
    </section>
  )
}

/** 8-b 코드 맞추기 결과 */
function CodeResult({ roomId, initial, onBack, onRerun, busy, onError }: {
  roomId: string; initial: CodeMatchView; onBack: () => void; onRerun: () => void; busy: boolean; onError: (e: unknown) => boolean
}) {
  const [m, setM] = useState(initial)
  useEffect(() => { setM(initial) }, [initial])

  // 이유를 쓰는 중이면 2초마다 확인
  useEffect(() => {
    if (m.status !== 'REASONING') return
    const ac = new AbortController()
    const t = setTimeout(() => {
      roomApi.codeMatchLatest(roomId, m.a.memberId, m.b.memberId, ac.signal).then(setM).catch(err => { if (!ac.signal.aborted) onError(err) })
    }, 2000)
    return () => { clearTimeout(t); ac.abort() }
  }, [roomId, m, onError])

  const d = codeMatchDisplay(m)
  const pct = (x: number) => `${Math.round(Math.max(0, Math.min(1, x)) * 100)}%`
  return (
    <section aria-label="코드 맞추기 결과" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className={s.topLeft}>
        <button type="button" className={s.back} aria-label="놀이터로" onClick={onBack}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 6l-6 6 6 6" /></svg>
        </button>
        <span className={s.step}>코드 맞추기</span>
      </div>

      <section className={sh.resultHero}>
        <div className={sh.compare} style={{ fontSize: 18, color: 'var(--r-peach)', gap: 10 }}>
          <Avatar nickname={m.a.nickname} colorIdx={m.a.colorIdx} characterId={m.a.avatarCharacterId} size={52} />
          <span aria-hidden="true">×</span>
          <Avatar nickname={m.b.nickname} colorIdx={m.b.colorIdx} characterId={m.b.avatarCharacterId} size={52} />
        </div>
        <span style={{ fontSize: 14, color: 'var(--r-silver)' }}>{m.a.nickname} × {m.b.nickname}</span>
        {d.showPercent
          ? <strong className={sh.resultHeadline}>코드가 <span style={{ color: 'var(--r-peach)' }}>{m.score}%</span> 맞아요</strong>
          : <strong className={sh.resultHeadline}>{d.headline}</strong>}
        {/* minSongs는 열리는 하한(설정값)이라 지금 곡 수는 두 사람 중 적은 쪽으로 */}
        {d.note && <span style={{ fontSize: 13, color: 'var(--r-accent-strong)' }}>{d.note}{!d.showPercent ? ` · 지금 ${Math.min(m.a.songCount, m.b.songCount)}곡` : ''}</span>}
      </section>

      {m.stale && (
        <div className={sh.pending} role="status">
          <span style={{ flex: 1 }}>그 뒤로 노래가 바뀌었어요. 다시 맞추면 새 결과가 나와요</span>
          <button type="button" className={sh.smallSecondary} style={{ height: 36 }} onClick={onRerun} disabled={busy}>{busy ? <Spinner size={14} /> : '다시 맞추기'}</button>
        </div>
      )}

      <section aria-label="겹친 것" className={sh.bars}>
        {d.showPercent ? (
          <>
            <Bar label="장르 비율" value={pct(m.parts.genre)} width={m.parts.genre} hint="두 사람 노래가 모인 구역의 비율" />
            <Bar label="지도 위 거리" value={m.parts.distance >= 0.7 ? '가까움' : m.parts.distance >= 0.4 ? '조금 떨어짐' : '멂'} width={m.parts.distance} hint="두 사람 노래가 모인 자리 사이 거리" />
            <Bar label="겹친 아티스트" value={`${m.sharedArtistCount}명`} width={m.parts.artist} hint={m.sharedArtists.join(' · ') || '아직 없어요'} />
            <Bar label="같이 담은 노래" value={`${m.sharedSongs}곡`} width={m.parts.song} hint={m.sharedSongs > 0 ? `금빛 위성 ${m.sharedSongs}개` : '아직 없어요'} />
          </>
        ) : (
          <>
            {d.shared.map(x => <div key={x} className={sh.sharedRow}>{x}</div>)}
            {m.sharedArtists.length > 0 && <span className={s.hint}>{m.sharedArtists.join(' · ')}</span>}
            {m.sharedGalaxies.length > 0 && <span className={s.hint}>구역: {m.sharedGalaxies.join(' · ')}</span>}
          </>
        )}
      </section>

      <section className={sh.why}>
        <h3>{d.showPercent ? '왜 이만큼 맞을까요' : '어떤 결이 닮았을까요'}</h3>
        {m.status === 'REASONING' && <p role="status"><Spinner size={12} /> 근거를 읽고 이유를 쓰는 중이에요…</p>}
        {m.status === 'DONE' && m.reason && <p>{m.reason}</p>}
        {(m.status === 'NO_REASON' || (m.status === 'DONE' && !m.reason)) && <p>근거로 쓸 수 있는 설명이 아직 부족해서 이유는 건너뛰었어요. 위 숫자는 그대로 맞아요.</p>}
        {m.evidence.length > 0 && (
          <div className={sh.evidence}>
            {m.evidence.map((e, i) => <a key={e.id} href={e.url} target="_blank" rel="noreferrer noopener">근거 {i + 1} · {sourceName(e.source)}</a>)}
          </div>
        )}
      </section>

      <button type="button" className={s.secondary} onClick={onBack}>다른 친구와</button>
    </section>
  )
}

function Bar({ label, value, width, hint }: { label: string; value: string; width: number; hint: string }) {
  return (
    <div className={sh.bar}>
      <div className={sh.barHead}><span>{label}</span><strong>{value}</strong></div>
      <div className={sh.barTrack} aria-hidden="true"><span style={{ width: `${Math.round(Math.max(0.03, Math.min(1, width)) * 100)}%` }} /></div>
      <span className={sh.barHint}>{hint}</span>
    </div>
  )
}
