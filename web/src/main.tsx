import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import './styles/tokens.css'
import { Home } from './pages/Home'
import { Pick } from './pages/Pick'
import { Upload } from './pages/Upload'
import { Result } from './pages/Result'

const router = createBrowserRouter([
  { path: '/', element: <Home /> },
  { path: '/pick', element: <Pick /> },
  { path: '/upload', element: <Upload /> },
  { path: '/r/:token', element: <Result /> },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
)
