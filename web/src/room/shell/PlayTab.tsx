import { useEffect, useState } from 'react'
import { memberColor, roomApi, RoomApiError, type CodeMatchView, type MemberRow } from '../roomApi'
import { codeMatchDisplay, sourceName, unlockText } from '../roomLogic'
import { Avatar } from '../components/Avatar'
import { Spinner } from '../components/RoomPage'
import type { ToastData } from './Toast'
import s from '../room.module.css'
import sh from './shell.module.css'
import p from '../play/play.module.css'
import { PairPlanets } from '../play/art/PairPlanets'
import { WorldCupArt } from '../play/art/WorldCupArt'
import { togglePick } from '../play/playArt'

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
    setPicked(prev => togglePick(prev, id))
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

  const pickedRows = picked.map(id => rows?.find(m => m.id === id)).filter((m): m is MemberRow => !!m)
  const side = (m?: MemberRow) => m ? { name: m.nickname, color: memberColor(m.colorIdx).bg } : null
  const ready = pickedRows.length === 2
  return (
    <section aria-label="놀이터" className={p.play}>
      <section aria-label="코드 맞추기" className={p.hero}>
        <div className={p.heroHead}>
          <strong>우리 코드, 몇 % 맞을까</strong>
          <span>두 사람의 음악 행성이 얼마나 겹치는지 봐요</span>
        </div>
        <PairPlanets a={side(pickedRows[0])} b={side(pickedRows[1])} />

        {rows === null && <p className={sh.center} role="status"><Spinner size={14} /> 불러오는 중…</p>}
        {rows && (
          <div className={p.chips} role="group" aria-label="두 사람 고르기">
            {rows.map(m => {
              const locked = unlockText(m.songCount, MIN_SONGS)
              const on = picked.includes(m.id)
              return (
                <button key={m.id} type="button" className={p.chip} aria-pressed={on} disabled={!!locked} onClick={() => toggle(m.id)}
                  aria-label={`${m.nickname}${m.me ? '(나)' : ''}, 노래 ${m.songCount}곡${locked ? `, ${locked}` : ''}`}
                  style={{ ['--chip-c' as string]: memberColor(m.colorIdx).bg }}>
                  <Avatar nickname={m.nickname} colorIdx={m.colorIdx} characterId={m.avatarCharacterId} size={44} />
                  <span>{m.nickname}{m.me ? ' · 나' : ''}<small>{locked ?? `${m.songCount}곡`}</small></span>
                </button>
              )
            })}
          </div>
        )}

        {ready ? (
          <button type="button" className={p.cta} disabled={busy} onClick={() => run(picked[0], picked[1])}
            style={{ ['--cta-a' as string]: memberColor(pickedRows[0].colorIdx).bg, ['--cta-b' as string]: memberColor(pickedRows[1].colorIdx).bg }}>
            {busy ? <Spinner /> : `${pickedRows[0].nickname} × ${pickedRows[1].nickname} 맞춰 보기`}
          </button>
        ) : (
          <p className={p.ctaHint}>{pickedRows.length === 1 ? `${pickedRows[0].nickname}님과 맞춰 볼 사람을 한 명 더 골라요` : '맞춰 볼 두 사람을 골라요'}<br />각자 노래 {MIN_SONGS}곡부터 할 수 있어요</p>
        )}
      </section>

      <section aria-label="취향 월드컵, 곧 열려요" className={p.cup}>
        <WorldCupArt />
        <div>
          <strong>취향 월드컵<span className={p.soonTag}>곧 열려요</span></strong>
          <span>우리 우주 노래끼리 한 곡씩 붙어서, 끝까지 남는 노래를 골라요</span>
        </div>
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
        {d.showPercent && (
          <div className={p.resultArt}>
            <PairPlanets score={m.score} a={{ name: m.a.nickname, color: memberColor(m.a.colorIdx).bg }} b={{ name: m.b.nickname, color: memberColor(m.b.colorIdx).bg }} />
          </div>
        )}
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
