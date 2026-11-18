import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버 15173. /api는 로컬 api(18080)로 넘긴다 (운영의 CloudFront /api/* 와 같은 모양)
// 임시 외부 공개(Cloudflare Quick Tunnel, *.trycloudflare.com)로 들어온 요청은 업로드·관리자 경로를 막는다.
// 외부 사람의 시청 기록이 이 맥의 DB에 쌓이지 않게, 관리자 화면이 밖에 보이지 않게 (개발 서버 전용)
const TUNNEL = /\.trycloudflare\.com$/i
const BLOCKED = [/^\/upload/, /^\/admin/, /^\/api\/admin/, /^\/api\/sessions\/?$/, /^\/api\/sessions\/[^/]+\/(upload-url|seeds)/]
const tunnelGuard: Plugin = {
  name: 'tunnel-guard',
  configureServer(server) {
    server.middlewares.use((req, res, next) => {
      const host = (req.headers['x-forwarded-host'] ?? req.headers.host ?? '').toString().split(':')[0]
      if (TUNNEL.test(host) && BLOCKED.some(r => r.test(req.url ?? ''))) {
        res.statusCode = 403
        res.setHeader('content-type', 'text/plain; charset=utf-8')
        res.end('지금은 열려 있지 않은 화면이에요.')
        return
      }
      next()
    })
  },
}

// 분위기로 가수 찾기 가짜 응답 (vibe-search-rag.md 4-2). 백엔드가 생기기 전 화면 확인용, VIBE_MOCK=1 npm run dev 일 때만.
// 질문에 "없음"이 들어가면 notFound, "실패"면 FAILED, "한도"면 429. 나머지 /api는 그대로 18080으로 간다
const vibeMock: Plugin = {
  name: 'vibe-mock',
  configureServer(server) {
    if (process.env.VIBE_MOCK !== '1') return
    type Job = { id: number; query: string; at: number; createdAt: string }
    const jobs = new Map<number, Job>()
    let seq = 0
    const send = (res: import('node:http').ServerResponse, status: number, body: unknown) => {
      res.statusCode = status
      res.setHeader('content-type', 'application/json')
      res.end(JSON.stringify(body))
    }
    const view = (j: Job, rid: string) => {
      const done = Date.now() - j.at > 2500
      const failed = j.query.includes('실패')
      const notFound = j.query.includes('없음')
      const status = !done ? 'PENDING' : failed ? 'FAILED' : 'DONE'
      const img = (id: number) => `/api/rooms/${rid}/artists/${id}/image`
      const picks = status !== 'DONE' || notFound ? [] : [
        { artistId: 101, name: '혁오', imageUrl: img(101), reason: '흐릿한 기타 톤과 나른한 보컬이 비 오는 밤의 축축한 공기와 닮았어요. [1]', inRoomBy: ['민지'] },
        { artistId: 102, name: '새소년', imageUrl: img(102), reason: '몽환적인 사이키델릭 사운드로 밤의 분위기를 만들어요. [2]', inRoomBy: [] },
        { artistId: 103, name: 'The Black Skirts', imageUrl: null, reason: null, inRoomBy: ['상우', '지훈', '하늘'] },
        { artistId: 104, name: 'Cigarettes After Sex', imageUrl: img(104), reason: '느린 템포와 속삭이는 듯한 보컬이 몽환적인 밤과 어울려요. 드림 팝 밴드예요. [3]', inRoomBy: [] },
      ]
      return {
        id: j.id, query: j.query, status, createdAt: j.createdAt, picks, notFound: status === 'DONE' && notFound,
        evidence: picks.length ? [
          { id: 1, source: 'WIKIPEDIA', url: 'https://ko.wikipedia.org/wiki/%ED%98%81%EC%98%A4' },
          { id: 2, source: 'LASTFM', url: 'https://www.last.fm/music/SE+SO+NEON' },
          { id: 3, source: 'WIKIPEDIA', url: 'https://en.wikipedia.org/wiki/Cigarettes_After_Sex' },
        ] : [],
      }
    }
    server.middlewares.use((req, res, next) => {
      const m = /^\/api\/rooms\/([^/]+)\/vibe-search(?:\/(\d+))?$/.exec((req.url ?? '').split('?')[0])
      if (!m) return next()
      const rid = m[1]
      if (req.method === 'GET' && m[2]) {
        const j = jobs.get(Number(m[2]))
        return j ? send(res, 200, view(j, rid)) : send(res, 404, { error: 'NOT_FOUND' })
      }
      if (req.method !== 'POST' || m[2]) return next()
      let raw = ''
      req.on('data', c => { raw += c })
      req.on('end', () => {
        const q = String((JSON.parse(raw || '{}') as { q?: unknown }).q ?? '').trim()
        const n = Array.from(q).length
        if (n < 2 || n > 100) return send(res, 400, { error: 'QUERY_LENGTH' })
        if (q.includes('한도')) return send(res, 429, { error: 'DAILY_LIMIT' })
        const same = [...jobs.values()].find(j => j.query === q && !q.includes('실패'))
        if (same) return send(res, 200, view(same, rid))
        const j = { id: ++seq, query: q, at: Date.now(), createdAt: new Date().toISOString() }
        jobs.set(j.id, j)
        send(res, 202, { id: j.id, status: 'PENDING' })
      })
    })
  },
}

export default defineConfig({
  plugins: [react(), tunnelGuard, vibeMock],
  server: {
    allowedHosts: ['.trycloudflare.com'],
    port: 15173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:18080',
        // 휴대폰 등 같은 와이파이 기기(--host)로 열면 Origin이 http://192.168.x.x:15173이 되어 api CORS(web-origin)에 막힌다.
        // 브라우저 입장에선 같은 출처라, 중계할 때 Origin을 api가 아는 개발 주소로 바꿔 준다 (개발 서버 전용)
        configure: proxy => {
          proxy.on('proxyReq', req => { if (req.getHeader('origin')) req.setHeader('origin', 'http://localhost:15173') })
        },
      },
    },
    // 약관 문안(../docs/legal/*.md)을 ?raw로 읽는다
    fs: { allow: ['.', '../docs/legal'] },
  },
})
