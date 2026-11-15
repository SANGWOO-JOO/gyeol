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

export default defineConfig({
  plugins: [react(), tunnelGuard],
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
