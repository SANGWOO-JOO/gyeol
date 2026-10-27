import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { api, ApiError, putToS3 } from '../api'
import { Alert, Layout } from '../components/Layout'
import { MAX_UPLOAD_BYTES, topVideoIds, uploadBody, VIDEO_CHECK_LIMIT } from '../takeout/filter'
import type { WorkerResponse } from '../takeout/filter.worker'
import ui from '../styles/ui.module.css'

type Ready = Extract<WorkerResponse, { ok: true }>
type Step = 'pick' | 'reading' | 'preview' | 'checking' | 'uploading'

const date = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString('ko-KR') : '-')
const num = (n: number) => n.toLocaleString()
const SHORT_DAYS = 180

export function Upload() {
  const navigate = useNavigate()
  const [step, setStep] = useState<Step>('pick')
  const [error, setError] = useState<string | null>(null)
  const [ready, setReady] = useState<Ready | null>(null)
  const [added, setAdded] = useState<number | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  function read(file: File) {
    setError(null); setReady(null); setAdded(null); setStep('reading')
    const worker = new Worker(new URL('../takeout/filter.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (e: MessageEvent<WorkerResponse>) => {
      worker.terminate()
      if (!e.data.ok) { setError(e.data.message); setStep('pick'); return }
      if (e.data.result.music === 0) { setError('음악 기록을 찾지 못했어요. 유튜브 뮤직 앱이나 "- Topic" 채널로 들은 기록이 있어야 해요.'); setStep('pick'); return }
      setReady(e.data); setStep('preview')
    }
    worker.onerror = () => { worker.terminate(); setError('파일을 읽지 못했어요.'); setStep('pick') }
    worker.postMessage(file)
  }

  async function upload() {
    if (!ready) return
    setStep('checking'); setError(null)
    try {
      const s = await api.createSession('TAKEOUT')
      // L2-3b: 미분류 영상 중 재생 상위만 ID로 음악 판정을 받는다 (제목은 보내지 않음). 판정을 못 하면 음악 기록만 올린다
      const ids = topVideoIds(ready.unclassifiedItems)
      const music = ids.length ? new Set((await api.videoCheck(s.token, ids).catch(() => null))?.musicIds ?? []) : new Set<string>()
      const extra = ready.unclassifiedItems.filter(it => it.videoId && music.has(it.videoId))
      setAdded(extra.length)

      setStep('uploading')
      const body = uploadBody([...ready.items, ...extra])
      const bytes = new TextEncoder().encode(body).length
      if (bytes > MAX_UPLOAD_BYTES) throw new ApiError(413, 'TOO_LARGE', '음악 기록이 너무 많아 올릴 수 없어요 (20MB 초과).')
      const ticket = await api.uploadUrl(s.token, {
        totalCount: ready.result.total, musicCount: ready.result.music + extra.length,
        unclassifiedCount: ready.result.unclassified - extra.length, byteSize: bytes,
      })
      await putToS3(ticket, body)
      navigate(s.resultUrl)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '올리지 못했어요.')
      setStep('preview')
    }
  }

  const r = ready?.result
  const days = r?.periodStart && r.periodEnd ? (Date.parse(r.periodEnd) - Date.parse(r.periodStart)) / 86_400_000 : null

  return (
    <Layout>
      <div className={ui.stack}>
        <h1 style={{ fontSize: 24 }}>유튜브 청취 기록 올리기</h1>

        <div className={ui.card}>
          <ol style={{ margin: 0, paddingLeft: 20 }} className={ui.small}>
            <li><a href="https://takeout.google.com" target="_blank" rel="noreferrer">Google Takeout</a>에서 <b>내 활동</b>만 선택하고, 활동 기록 옵션에서 <b>YouTube</b>와 형식 <b>JSON</b>을 고릅니다.</li>
            <li>받은 압축 파일을 풀면 <code>Takeout/내 활동/YouTube/내활동.json</code> 파일이 있어요.</li>
            <li>그 파일을 아래에서 고르면 브라우저 안에서 먼저 걸러요. <b>검색 기록과 일반 영상 제목은 보내지 않아요.</b></li>
          </ol>
        </div>

        <input ref={inputRef} type="file" accept=".json,application/json" hidden
          onChange={e => { const f = e.target.files?.[0]; if (f) read(f); e.target.value = '' }} />

        {error && <Alert>{error}</Alert>}

        {(step === 'pick' || step === 'reading') && (
          <button className={`${ui.button} ${ui.primary}`} onClick={() => inputRef.current?.click()} disabled={step === 'reading'}>
            {step === 'reading' ? '파일을 읽고 거르는 중…' : 'JSON 파일 고르기'}
          </button>
        )}

        {r && ready && (step === 'preview' || step === 'checking' || step === 'uploading') && (
          <div className={ui.stack}>
            <div className={ui.grid}>
              <div className={ui.stat}><div className={ui.statLabel}>시청 기록</div><div className={ui.statValue}>{num(r.total)}</div></div>
              <div className={ui.stat}><div className={ui.statLabel}>보낼 음악 기록</div><div className={ui.statValue}>{num(r.music)}</div></div>
              <div className={ui.stat}><div className={ui.statLabel}>아직 판정 못 함</div><div className={ui.statValue}>{num(r.unclassified)}</div></div>
            </div>
            <span className={ui.small}>
              기간 {date(r.periodStart)} ~ {date(r.periodEnd)} ·
              검색·기타 활동 {num(r.otherActivity)}건과 삭제된 영상 {num(r.removed)}건은 보내지 않아요 · 거르는 데 {(ready.ms / 1000).toFixed(1)}초
            </span>
            <Alert kind="info">
              뮤직 앱과 "- Topic" 채널 기록은 바로 음악으로 봐요. 나머지 일반 영상 {num(r.unclassified)}건은 많이 본 영상 {num(VIDEO_CHECK_LIMIT)}개까지
              음악인지 YouTube에 확인해서, 음악인 것만 함께 올려요. 확인할 때는 영상 ID만 보내고 제목은 보내지 않아요.
              {added !== null && ` 확인 결과 ${num(added)}건을 더했어요.`}
            </Alert>
            {days !== null && days < SHORT_DAYS && (
              <Alert kind="warn">기록 기간이 짧아요({Math.round(days)}일). "YouTube 및 YouTube Music"으로 받은 파일은 최근 기록 일부만 와요. "내 활동 → YouTube"로 다시 받으면 더 길게 올 수 있어요.</Alert>
            )}
            <div className={ui.row} style={{ justifyContent: 'flex-end' }}>
              <button className={ui.button} onClick={() => inputRef.current?.click()} disabled={step !== 'preview'}>다른 파일</button>
              <button className={`${ui.button} ${ui.primary}`} onClick={upload} disabled={step !== 'preview'}>
                {step === 'checking' ? '음악 영상 확인 중…' : step === 'uploading' ? '올리는 중…' : `음악 기록 올리기`}
              </button>
            </div>
          </div>
        )}
      </div>
    </Layout>
  )
}
