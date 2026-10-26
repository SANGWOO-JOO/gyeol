import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, ApiError, type SessionView, type Stats } from '../api'
import { BarChart } from '../components/BarChart'
import { Alert, Layout } from '../components/Layout'
import { Tile } from '../components/Tile'
import ui from '../styles/ui.module.css'

const POLL_MS = 2000
const WEEKDAYS = ['월', '화', '수', '목', '금', '토', '일']
const num = (n: number) => n.toLocaleString()
const date = (d: string | null) => (d ? d.replaceAll('-', '.') : '-')

export function Result() {
  const { token = '' } = useParams()
  const navigate = useNavigate()
  const [session, setSession] = useState<SessionView | null>(null)
  const [stats, setStats] = useState<Stats | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  // 처리 중이면 2초마다 상태 확인, 끝나면 통계를 한 번 가져온다
  useEffect(() => {
    let stop = false, statsLoaded = false, timer: ReturnType<typeof setTimeout>
    async function tick() {
      try {
        const s = await api.session(token)
        if (stop) return
        setSession(s)
        if (s.status === 'DONE' && s.entry === 'TAKEOUT' && !statsLoaded) { statsLoaded = true; setStats(await api.stats(token)) }
        // 처리 중이거나, 통계는 나왔지만 곡 확인(식별)이 아직 진행 중이면 계속 확인
        if ((s.status !== 'DONE' && s.status !== 'FAILED') || identifying(s)) timer = setTimeout(tick, POLL_MS)
      } catch (e) {
        if (!stop) setError(e instanceof ApiError && e.status === 404 ? '결과를 찾을 수 없어요. 링크가 틀렸거나 기간이 지나 삭제되었어요.' : '결과를 불러오지 못했어요.')
      }
    }
    tick()
    return () => { stop = true; clearTimeout(timer) }
  }, [token])

  async function remove() {
    if (!confirm('이 결과와 올린 기록을 지금 삭제할까요? 되돌릴 수 없어요.')) return
    try { await api.remove(token); navigate('/') } catch { setError('삭제하지 못했어요. 잠시 뒤 다시 시도해 주세요.') }
  }

  async function copy() {
    await navigator.clipboard.writeText(location.href)
    setCopied(true); setTimeout(() => setCopied(false), 1500)
  }

  return (
    <Layout>
      <div className={ui.stack} style={{ gap: 24 }}>
        {error && <Alert>{error}</Alert>}
        {!session && !error && <span className={ui.small}>불러오는 중…</span>}

        {session && session.status !== 'DONE' && session.status !== 'FAILED' && (
          <div className={ui.card}>
            <strong>기록을 정리하고 있어요</strong>
            <p className={ui.small} style={{ margin: '4px 0 0' }}>
              {session.counts.music ? `음악 기록 ${num(session.counts.music)}건을 저장하는 중이에요. ` : ''}보통 몇 초 걸려요. 이 페이지를 닫아도 링크로 다시 볼 수 있어요.
            </p>
          </div>
        )}

        {session?.status === 'FAILED' && (
          <Alert>기록을 처리하지 못했어요{session.error ? `: ${session.error}` : ''}. <Link to="/upload">다시 올리기</Link></Alert>
        )}

        {session?.status === 'DONE' && session.entry === 'SEEDS' && <Seeds session={session} />}
        {session?.status === 'DONE' && session.identify && <IdentifyProgress p={session.identify} />}
        {session?.status === 'DONE' && stats && <Report stats={stats} />}

        {session && (
          <div className={ui.row} style={{ justifyContent: 'space-between', borderTop: '1px solid var(--border)', paddingTop: 16 }}>
            <span className={ui.small}>{new Date(session.expiresAt).toLocaleDateString('ko-KR')}까지 이 링크로 볼 수 있어요.</span>
            <div className={ui.row}>
              <button className={ui.button} onClick={copy}>{copied ? '복사했어요' : '링크 복사'}</button>
              <button className={`${ui.button} ${ui.danger}`} onClick={remove}>지금 삭제</button>
            </div>
          </div>
        )}
      </div>
    </Layout>
  )
}

const identifying = (s: SessionView) => !!s.identify && s.identify.done + s.identify.failed < s.identify.target

/** 재생 상위 곡을 MusicBrainz로 확인하는 진행 상황 (L2-1). 곡 단위 결과 화면은 L2-2 */
function IdentifyProgress({ p }: { p: NonNullable<SessionView['identify']> }) {
  const finished = p.done + p.failed
  const running = finished < p.target
  return (
    <div className={ui.card} style={{ padding: 16 }}>
      <div className={ui.row} style={{ justifyContent: 'space-between' }}>
        <strong>{running ? '많이 들은 곡을 확인하고 있어요' : '많이 들은 곡 확인을 마쳤어요'}</strong>
        <span className={ui.count}>{num(finished)}/{num(p.target)}</span>
      </div>
      <div style={{ height: 6, borderRadius: 3, background: 'var(--surface-2)', margin: '10px 0 8px', overflow: 'hidden' }}>
        <div style={{ width: `${(finished / Math.max(1, p.target)) * 100}%`, height: '100%', background: 'var(--accent-bar)', transition: 'width .4s' }} />
      </div>
      <span className={ui.small}>
        찾은 곡 {num(p.matched)} · 못 찾음 {num(p.notFound)}{p.failed ? ` · 오류 ${num(p.failed)}` : ''}
        {running ? ' · 곡마다 몇 초씩 걸려요. 이 페이지를 닫아도 계속 진행돼요.' : ''}
      </span>
    </div>
  )
}

function Seeds({ session }: { session: SessionView }) {
  return (
    <div className={ui.stack}>
      <h1 style={{ fontSize: 24 }}>고른 곡 {session.seeds?.length ?? 0}개</h1>
      <ul className={`${ui.list} ${ui.card}`} style={{ padding: '4px 16px' }}>
        {session.seeds?.map((s, i) => (
          <li key={i} className={ui.listItem}>
            <Tile text={s.artist} />
            <div className={ui.grow}><div>{s.title}</div><div className={ui.small}>{s.artist}</div></div>
          </li>
        ))}
      </ul>
      <Alert kind="info">취향 분석과 추천은 준비 중이에요. 이 링크로 나중에 다시 확인할 수 있어요.</Alert>
    </div>
  )
}

function Report({ stats }: { stats: Stats }) {
  const { period, totals, coverage } = stats
  const years = period.days ? period.days / 365 : 0
  const span = years >= 1 ? `${years.toFixed(1)}년` : `${period.days ?? 0}일`
  const months = stats.byMonth.map(m => m.month)
  const monthTicks = months.map((m, i) => (m.endsWith('-01') || i === 0 ? i : -1)).filter(i => i >= 0)
  const appShare = totals.plays ? Math.round((stats.bySource.musicApp / totals.plays) * 100) : 0

  return (
    <div className={ui.stack} style={{ gap: 28 }}>
      <section className={ui.stack} style={{ gap: 4 }}>
        <span className={ui.muted}>{date(period.start)} ~ {date(period.end)}</span>
        <h1 style={{ fontSize: 32 }}>{span} 동안 음악을 {num(totals.plays)}번 들었어요</h1>
        <span className={ui.muted}>서로 다른 곡 {num(totals.uniqueTitles)}개 · 가수·채널 {num(totals.uniqueChannels)}곳 · 뮤직 앱 {appShare}%</span>
      </section>

      <section className={ui.stack} style={{ gap: 8 }}>
        <h2 style={{ fontSize: 18 }}>많이 들은 곡</h2>
        <ol className={`${ui.list} ${ui.card}`} style={{ padding: '4px 16px' }}>
          {stats.topTitles.slice(0, 10).map((t, i) => (
            <li key={i} className={ui.listItem}>
              <span className={ui.rank}>{i + 1}</span>
              <Tile text={t.channel ?? t.title} />
              <div className={ui.grow}>
                <div className={ui.ellipsis}>{t.title}</div>
                <div className={`${ui.small} ${ui.ellipsis}`}>{t.channel}</div>
              </div>
              <span className={ui.count}>{num(t.plays)}회</span>
            </li>
          ))}
        </ol>
      </section>

      <section className={ui.stack} style={{ gap: 8 }}>
        <h2 style={{ fontSize: 18 }}>많이 들은 가수·채널</h2>
        <ol className={`${ui.list} ${ui.card}`} style={{ padding: '4px 16px' }}>
          {stats.topChannels.slice(0, 10).map((c, i) => (
            <li key={i} className={ui.listItem}>
              <span className={ui.rank}>{i + 1}</span>
              <Tile text={c.channel} size={32} />
              <div className={`${ui.grow} ${ui.ellipsis}`}>{c.channel}</div>
              <span className={ui.count}>{num(c.plays)}회</span>
            </li>
          ))}
        </ol>
      </section>

      <section className={`${ui.card} ${ui.stack}`}>
        <h2 style={{ fontSize: 18 }}>언제 들었나</h2>
        <span className={ui.small}>시간대 (한국 시간)</span>
        <BarChart title="시간대별 재생" values={stats.byHour} labels={stats.byHour.map((_, h) => `${h}시`)} ticks={[0, 6, 12, 18, 23]} />
        <span className={ui.small}>요일</span>
        <BarChart title="요일별 재생" values={stats.byWeekday} labels={WEEKDAYS} height={120} />
        {months.length > 1 && <>
          <span className={ui.small}>월별</span>
          <BarChart title="월별 재생" values={stats.byMonth.map(m => m.plays)} labels={months.map(m => m.replace('-', '.'))} ticks={monthTicks} />
        </>}
      </section>

      {coverage.unclassified != null && coverage.unclassified > 0 && (
        <Alert kind="info">
          뮤직 앱과 "- Topic" 채널 기록 {num(coverage.music ?? 0)}건으로 분석했어요. 일반 유튜브의 뮤직비디오 등 {num(coverage.unclassified)}건은 아직 판정하지 못해 빠져 있어요.
        </Alert>
      )}
    </div>
  )
}
