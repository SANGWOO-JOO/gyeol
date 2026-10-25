import { Link } from 'react-router'
import { Layout } from '../components/Layout'
import ui from '../styles/ui.module.css'

export function Home() {
  return (
    <Layout>
      <div className={ui.stack} style={{ gap: 28 }}>
        <div className={ui.stack} style={{ gap: 8 }}>
          <h1 style={{ fontSize: 28 }}>내 음악 취향의 결을 찾아요</h1>
          <p className={ui.muted} style={{ margin: 0 }}>좋아하는 곡 몇 개나 유튜브 청취 기록으로 시작하세요. 계정은 필요 없고, 결과는 링크로 30일 동안 볼 수 있어요.</p>
        </div>
        <div className={ui.grid} style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))' }}>
          <Link to="/pick" className={ui.card} style={{ textDecoration: 'none', color: 'inherit' }}>
            <div className={ui.stack} style={{ gap: 6 }}>
              <strong>곡 3~5개 고르기</strong>
              <span className={ui.small}>좋아하는 곡을 검색해서 골라요. 1분이면 끝나요.</span>
            </div>
          </Link>
          <Link to="/upload" className={ui.card} style={{ textDecoration: 'none', color: 'inherit' }}>
            <div className={ui.stack} style={{ gap: 6 }}>
              <strong>청취 기록 올리기</strong>
              <span className={ui.small}>Google Takeout으로 받은 유튜브 기록을 올려요. 음악 기록만 보내요.</span>
            </div>
          </Link>
        </div>
      </div>
    </Layout>
  )
}
