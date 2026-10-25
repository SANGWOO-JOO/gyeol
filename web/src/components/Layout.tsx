import type { ReactNode } from 'react'
import { Link } from 'react-router'
import ui from '../styles/ui.module.css'

export function Layout({ children }: { children: ReactNode }) {
  return (
    <main className={ui.page}>
      <header className={ui.header}>
        <Link to="/" className={ui.logo}>내 음악 결</Link>
      </header>
      {children}
    </main>
  )
}

export function Alert({ kind = 'error', children }: { kind?: 'error' | 'warn' | 'info'; children: ReactNode }) {
  const cls = kind === 'error' ? ui.alertError : kind === 'warn' ? ui.alertWarn : ui.alertInfo
  return <div role={kind === 'error' ? 'alert' : 'status'} className={`${ui.alert} ${cls}`}>{children}</div>
}
