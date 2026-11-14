import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버 15173. /api는 로컬 api(18080)로 넘긴다 (운영의 CloudFront /api/* 와 같은 모양)
export default defineConfig({
  plugins: [react()],
  server: {
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
