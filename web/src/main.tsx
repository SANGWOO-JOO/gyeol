import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import './styles/fonts'
import './styles/tokens.css'
import './styles/room.css'
import { Pick } from './pages/Pick'
import { Upload } from './pages/Upload'
import { Result } from './pages/Result'
import { Landing } from './room/pages/Landing'
import { CreateRoom } from './room/pages/CreateRoom'
import { RoomEntry } from './room/pages/RoomEntry'
import { LegalPage } from './legal/LegalPage'
import { AdminAudit, AdminLogin, AdminRoom, AdminRooms } from './admin/AdminPages'

const router = createBrowserRouter([
  // 음악 방 (docs/todo/room-web.md). 첫 화면은 방 만들기 중심, 청취 분석·곡 고르기는 작은 링크
  { path: '/', element: <Landing /> },
  { path: '/rooms/new', element: <CreateRoom /> },
  { path: '/rooms/:rid', element: <RoomEntry /> },
  // 이용약관·개인정보 처리방침 (문안은 docs/legal/*.md)
  { path: '/terms', element: <LegalPage doc="terms" /> },
  { path: '/privacy', element: <LegalPage doc="privacy" /> },
  // 관리자 (room-web.md 8단계, 배포 때 지정 IP만)
  { path: '/admin', element: <AdminLogin /> },
  { path: '/admin/rooms', element: <AdminRooms /> },
  { path: '/admin/rooms/:rid', element: <AdminRoom /> },
  { path: '/admin/audit', element: <AdminAudit /> },
  // 기존 청취 분석·곡 고르기 (초록 테마 그대로)
  { path: '/pick', element: <Pick /> },
  { path: '/upload', element: <Upload /> },
  { path: '/r/:token', element: <Result /> },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
)
