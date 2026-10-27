import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, ApiError, type Recommendations, type SessionView, type Stats } from '../api'
import { BarChart } from '../components/BarChart'
import { Alert, Layout } from '../components/Layout'
import { Cover, Tile } from '../components/Tile'
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
    // 곡 확인이 진행되면 순위가 바뀌므로 통계를 다시 받는다 (실제 규모 통계는 약 1초라 10초에 한 번, 끝나면 한 번 더)
    let stop = false, lastDone = -1, lastFetch = 0, coverWaits = 0, timer: ReturnType<typeof setTimeout>
    async function tick() {
      try {
        const s = await api.session(token)
        if (stop) return
        setSession(s)
        const done = s.identify ? s.identify.done + s.identify.failed : 0
        const due = !identifying(s) || Date.now() - lastFetch > 10_000
        if (s.status === 'DONE' && s.entry === 'TAKEOUT' && done !== lastDone && due) {
          lastDone = done; lastFetch = Date.now()
          setStats(await api.stats(token))
        }
        // 처리 중이거나, 통계는 나왔지만 곡 확인(식별)이 아직 진행 중이면 계속 확인
        const waitCover = coverPending(s) && coverWaits++ < 30 // 최대 1분 (표지가 없는 곡도 있음)
        if ((s.status !== 'DONE' && s.status !== 'FAILED') || identifying(s) || waitCover) timer = setTimeout(tick, POLL_MS)
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
        {session?.status === 'DONE' && session.entry === 'TAKEOUT' && session.identify && <IdentifyProgress p={session.identify} />}
        {session?.status === 'DONE' && session.enrich && session.enrich.done + session.enrich.failed < session.enrich.target && (
          <span className={ui.small}>추천 근거가 될 곡 정보를 모으는 중이에요 ({num(session.enrich.done + session.enrich.failed)}/{num(session.enrich.target)})</span>
        )}
        {session?.status === 'DONE' && session.enrich && session.enrich.done + session.enrich.failed >= session.enrich.target && <Recommend token={token} />}
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

const identifying = (s: SessionView) =>
  (!!s.identify && s.identify.done + s.identify.failed < s.identify.target) || (!!s.enrich && s.enrich.done + s.enrich.failed < s.enrich.target)
// 표지는 식별 뒤 따로 붙는다: 찾은 고른 곡에 표지 확인이 남아 있으면 조금 더 기다린다
const coverPending = (s: SessionView) => !!s.seeds?.some(x => x.releaseTitle && !x.coverUrl)

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
            <Cover url={s.coverUrl} text={s.artist} />
            <div className={ui.grow}>
              <div>{s.title}</div>
              <div className={ui.small}>{s.artist}{s.releaseTitle ? ` · ${s.releaseTitle}${s.releaseYear ? ` (${s.releaseYear})` : ''}` : ''}</div>
            </div>
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
  const identifiedShare = stats.identified.totalPlays ? Math.round((stats.identified.plays / stats.identified.totalPlays) * 100) : 0
  const hasCover = stats.topSongs.some(t => t.coverUrl)

  return (
    <div className={ui.stack} style={{ gap: 28 }}>
      <section className={ui.stack} style={{ gap: 4 }}>
        <span className={ui.muted}>{date(period.start)} ~ {date(period.end)}</span>
        <h1 style={{ fontSize: 32 }}>{span} 동안 음악을 {num(totals.plays)}번 들었어요</h1>
        <span className={ui.muted}>서로 다른 곡 {num(totals.uniqueTitles)}개 · 가수·채널 {num(totals.uniqueChannels)}곳 · 뮤직 앱 {appShare}%</span>
      </section>

      <section className={ui.stack} style={{ gap: 8 }}>
        <div className={ui.row} style={{ justifyContent: 'space-between' }}>
          <h2 style={{ fontSize: 18 }}>많이 들은 곡</h2>
          {identifiedShare > 0 && <span className={ui.small}>곡 확인: 재생의 {identifiedShare}%</span>}
        </div>
        <ol className={`${ui.list} ${ui.card}`} style={{ padding: '4px 16px' }}>
          {stats.topSongs.slice(0, 10).map((t, i) => (
            <li key={i} className={ui.listItem}>
              <span className={ui.rank}>{i + 1}</span>
              <Cover url={t.coverUrl} text={t.artist ?? t.title} />
              <div className={ui.grow}>
                <div className={ui.ellipsis}>{t.title}</div>
                <div className={`${ui.small} ${ui.ellipsis}`}>
                  {t.artist}{t.release?.title ? ` · ${t.release.title}${t.release.year ? ` (${t.release.year})` : ''}` : ''}
                </div>
              </div>
              <span className={ui.count}>{num(t.plays)}회</span>
            </li>
          ))}
        </ol>
      </section>

      <section className={ui.stack} style={{ gap: 8 }}>
        <h2 style={{ fontSize: 18 }}>많이 들은 가수</h2>
        <ol className={`${ui.list} ${ui.card}`} style={{ padding: '4px 16px' }}>
          {stats.topArtists.slice(0, 10).map((a, i) => (
            <li key={i} className={ui.listItem}>
              <span className={ui.rank}>{i + 1}</span>
              <Tile text={a.name} size={32} />
              <div className={`${ui.grow} ${ui.ellipsis}`}>{a.name}</div>
              <span className={ui.count}>{num(a.plays)}회</span>
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
      {hasCover && (
        <span className={ui.small}>
          곡 정보: <a href="https://musicbrainz.org" target="_blank" rel="noreferrer">MusicBrainz</a> ·
          앨범 표지: <a href="https://coverartarchive.org" target="_blank" rel="noreferrer">Cover Art Archive</a>
        </span>
      )}
    </div>
  )
}

const SOURCE_NAMES: Record<string, string> = { WIKIPEDIA: '위키백과', WIKIDATA: '위키데이터', LASTFM: 'Last.fm', MUSICBRAINZ: 'MusicBrainz' }

/** L3-4 결이 맞는 곡 20. 후보 곡 정보를 모으는 동안(수 분) 5초마다 확인한다 */
function Recommend({ token }: { token: string }) {
  const [rec, setRec] = useState<Recommendations | null>(null)
  useEffect(() => {
    let stop = false, timer: ReturnType<typeof setTimeout>, tries = 0
    async function tick() {
      try {
        const r = await api.recommendations(token)
        if (stop) return
        setRec(r)
        if (r.status !== 'READY' && tries++ < 240) timer = setTimeout(tick, 5000) // 최대 20분
      } catch { /* 추천은 덤: 실패해도 나머지 화면은 그대로 */ }
    }
    tick()
    return () => { stop = true; clearTimeout(timer) }
  }, [token])

  if (!rec) return null
  if (rec.status !== 'READY') {
    return (
      <span className={ui.small}>
        결이 맞는 곡을 찾는 중이에요{rec.candidatesTotal ? ` (후보 ${num(rec.candidatesDone)}/${num(rec.candidatesTotal)}곡 확인)` : ''}
      </span>
    )
  }
  return (
    <section className={ui.stack} style={{ gap: 8 }}>
      <h2 style={{ fontSize: 18 }}>결이 맞는 곡 {rec.items.length}</h2>
      {rec.summary && <p className={ui.small} style={{ margin: 0 }}>{rec.summary}</p>}
      <ol className={`${ui.list} ${ui.card}`} style={{ padding: '4px 16px' }}>
        {rec.items.map(t => (
          <li key={t.trackId} className={ui.listItem} style={{ alignItems: 'flex-start' }}>
            <span className={ui.rank}>{t.rank}</span>
            <Cover url={t.coverUrl} text={t.artist} />
            <div className={ui.grow}>
              <div className={ui.ellipsis}>{t.title}</div>
              <div className={`${ui.small} ${ui.ellipsis}`}>{t.artist}{t.releaseTitle ? ` · ${t.releaseTitle}` : ''}</div>
              <div className={ui.small} style={{ marginTop: 4 }}>
                {t.reason ?? '근거 자료가 부족해 점수로만 골랐어요.'}
                {t.evidence.map((e, i) => (
                  <a key={e.id} href={e.url} target="_blank" rel="noreferrer" style={{ marginLeft: 4 }}>[{SOURCE_NAMES[e.source] ?? e.source}{t.evidence.length > 1 ? ` ${i + 1}` : ''}]</a>
                ))}
              </div>
              {t.basedOn.length > 0 && (
                <div className={ui.small} style={{ marginTop: 2 }}>들은 곡: {t.basedOn.map(b => `${b.artist} - ${b.title}`).join(', ')}</div>
              )}
            </div>
          </li>
        ))}
      </ol>
    </section>
  )
}
