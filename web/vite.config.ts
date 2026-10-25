import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버 15173. /api는 로컬 api(18080)로 넘긴다 (운영의 CloudFront /api/* 와 같은 모양)
export default defineConfig({
  plugins: [react()],
  server: {
    port: 15173,
    strictPort: true,
    proxy: { '/api': 'http://localhost:18080' },
  },
})
