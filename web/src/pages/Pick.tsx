import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { api, ApiError, type Track } from '../api'
import { Alert, Layout } from '../components/Layout'
import { Tile } from '../components/Tile'
import ui from '../styles/ui.module.css'

const MIN = 3, MAX = 5
const key = (t: Track) => `${t.title}\u0000${t.artist}`.toLowerCase()

export function Pick() {
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const [results, setResults] = useState<Track[]>([])
  const [searching, setSearching] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [picked, setPicked] = useState<Track[]>([])
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  // 입력이 0.5초 멈추면 검색. 앞선 요청은 취소
  useEffect(() => {
    const query = q.trim()
    if (query.length < 2) { setResults([]); setSearchError(null); return }
    const ctrl = new AbortController()
    const timer = setTimeout(async () => {
      setSearching(true); setSearchError(null)
      try {
        setResults((await api.searchTracks(query, ctrl.signal)).items)
      } catch (e) {
        if (!ctrl.signal.aborted) setSearchError(e instanceof ApiError ? e.message : '검색하지 못했습니다.')
      } finally {
        if (!ctrl.signal.aborted) setSearching(false)
      }
    }, 500)
    return () => { clearTimeout(timer); ctrl.abort() }
  }, [q])

  const isPicked = (t: Track) => picked.some(p => key(p) === key(t))
  const toggle = (t: Track) =>
    setPicked(ps => isPicked(t) ? ps.filter(p => key(p) !== key(t)) : ps.length >= MAX ? ps : [...ps, t])

  async function submit() {
    setSaving(true); setSaveError(null)
    try {
      const s = await api.createSession('SEEDS')
      await api.saveSeeds(s.token, picked)
      navigate(s.resultUrl)
    } catch (e) {
      setSaveError(e instanceof ApiError ? e.message : '저장하지 못했습니다.')
      setSaving(false)
    }
  }

  return (
    <Layout>
      <div className={ui.stack}>
        <h1 style={{ fontSize: 24 }}>좋아하는 곡을 {MIN}~{MAX}개 골라 주세요</h1>

        <div className={ui.card} style={{ padding: 14, minHeight: 64 }}>
          {picked.length === 0
            ? <span className={ui.small}>아직 고른 곡이 없어요.</span>
            : <div className={ui.row}>{picked.map(t => (
                <span key={key(t)} className={ui.chip}>
                  {t.title} · {t.artist}
                  <button aria-label={`${t.title} 빼기`} onClick={() => toggle(t)}>✕</button>
                </span>))}
              </div>}
        </div>

        <input className={ui.input} value={q} onChange={e => setQ(e.target.value)} autoFocus
          placeholder="가수와 곡 이름, 예: 아이유 밤편지" aria-label="곡 검색" />

        {searchError && <Alert>{searchError}</Alert>}
        {searching && <span className={ui.small}>찾는 중…</span>}
        {!searching && !searchError && q.trim().length >= 2 && results.length === 0 && (
          <Alert kind="info">찾는 곡이 없어요. 가수와 곡 이름 사이를 띄우거나, 영문·원어 이름으로도 검색해 보세요.</Alert>
        )}

        {results.length > 0 && (
          <ul className={`${ui.list} ${ui.card}`} style={{ padding: '4px 16px' }}>
            {results.map(t => {
              const on = isPicked(t), full = !on && picked.length >= MAX
              return (
                <li key={key(t)} className={ui.listItem}>
                  <Tile text={t.artist} />
                  <div className={ui.grow}>
                    <div className={ui.ellipsis}>{t.title}</div>
                    <div className={`${ui.small} ${ui.ellipsis}`}>{t.artist}{t.releaseYear ? ` · ${t.releaseYear}` : ''}</div>
                  </div>
                  <button className={`${ui.button} ${on ? ui.primary : ''}`} onClick={() => toggle(t)} disabled={full}
                    title={full ? `최대 ${MAX}곡까지 고를 수 있어요` : undefined}>
                    {on ? '골랐어요' : '고르기'}
                  </button>
                </li>
              )
            })}
          </ul>
        )}

        {saveError && <Alert>{saveError}</Alert>}
        <div className={ui.row} style={{ justifyContent: 'space-between' }}>
          <span className={ui.small}>{picked.length}/{MAX}곡</span>
          <button className={`${ui.button} ${ui.primary}`} onClick={submit} disabled={picked.length < MIN || saving}>
            {saving ? '저장하는 중…' : picked.length < MIN ? `${MIN - picked.length}곡 더 골라 주세요` : '이 곡들로 시작하기'}
          </button>
        </div>
      </div>
    </Layout>
  )
}
