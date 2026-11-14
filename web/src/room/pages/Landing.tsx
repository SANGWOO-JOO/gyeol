import { useState } from 'react'
import { Link } from 'react-router'
import { keyStore } from '../keyStore'
import { NoteIcon, RoomPage } from '../components/RoomPage'
import { Wordmark } from '../components/Wordmark'
import s from '../room.module.css'
import o from '../components/outside.module.css'
import l from './Landing.module.css'

/** 1 첫 화면: 방 만들기 중심. 청취 기록·곡 고르기는 작은 링크로 남긴다 (room-plan 9절) */
export function Landing() {
  const [showMine, setShowMine] = useState(false)
  const mine = showMine ? keyStore.list() : []

  return (
    <RoomPage label="첫 화면">
      <header className={s.top}>
        <Wordmark />
        <button type="button" className={s.ghost} style={{ padding: 0 }} aria-expanded={showMine} onClick={() => setShowMine(v => !v)}>
          내 방 찾기
        </button>
      </header>

      {showMine && (
        <section className={`${s.note} ${o.notice}`} aria-label="이 기기에서 들어간 우주">
          {mine.length === 0 ? (
            <span>이 기기에서 들어간 우주가 없어요. 친구에게 받은 초대 링크를 다시 열어 주세요.</span>
          ) : (
            <ul className={l.mine}>
              {mine.map(k => (
                <li key={k.roomId}>
                  <Link to={`/rooms/${k.roomId}`}>{k.roomName}</Link>
                  <span className={s.hint}> · {k.nickname}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      <div className={l.hero}>
        <Hero />
      </div>

      <section className={s.section} style={{ gap: 12 }}>
        <h1 className={l.headline}>오늘 밤,<br />노래로 우주를 그려요</h1>
        <p className={s.lead} style={{ fontSize: 15 }}>친구와 노래를 모으면, 우리만의 지도가 생겨요.</p>
      </section>

      <div className={s.section} style={{ gap: 4 }}>
        <Link to="/rooms/new" className={`${s.primary} ${o.cta}`} style={{ height: 58 }}>
          <NoteIcon /> 친구랑 우주 그리기
        </Link>
        <div className={l.small}>
          <Link to="/upload">유튜브 청취 기록으로 보기</Link>
          <span aria-hidden="true">·</span>
          <Link to="/pick">좋아하는 곡으로 추천받기</Link>
        </div>
      </div>
    </RoomPage>
  )
}

/** 세 사람의 행성이 궤도로 이어지고, 같이 담은 노래(금빛 위성)가 그 사이를 도는 모습 */
function Hero() {
  return (
    <div className={l.heroBox}>
      <svg viewBox="0 0 342 300" fill="none" role="img" aria-label="친구들의 행성이 궤도로 이어지고 같이 담은 노래가 금빛 위성으로 도는 모습" className={l.heroSvg}>
        <defs>
          <radialGradient id="lp-a" cx="35%" cy="30%" r="75%"><stop offset="0" stopColor="#E6DCFF" /><stop offset=".55" stopColor="#B9A2FF" /><stop offset="1" stopColor="#5B47A8" /></radialGradient>
          <radialGradient id="lp-b" cx="35%" cy="30%" r="75%"><stop offset="0" stopColor="#FFE0EA" /><stop offset=".55" stopColor="#E3A6B8" /><stop offset="1" stopColor="#8A4A64" /></radialGradient>
          <radialGradient id="lp-c" cx="35%" cy="30%" r="75%"><stop offset="0" stopColor="#D8F4FF" /><stop offset=".55" stopColor="#8CC8E8" /><stop offset="1" stopColor="#3B6A8C" /></radialGradient>
          <radialGradient id="lp-glow"><stop offset="0" stopColor="#B9A2FF" stopOpacity=".32" /><stop offset="1" stopColor="#B9A2FF" stopOpacity="0" /></radialGradient>
        </defs>
        <circle cx="171" cy="150" r="140" fill="url(#lp-glow)" />
        <ellipse cx="171" cy="150" rx="148" ry="58" stroke="#E6DCFF" strokeOpacity=".16" strokeDasharray="2 6" strokeLinecap="round" transform="rotate(-14 171 150)" />
        <path d="M86 112 Q171 70 252 108" stroke="#E6DCFF" strokeOpacity=".28" strokeWidth="1.2" strokeDasharray="1 5" strokeLinecap="round" />
        <path d="M86 112 Q120 190 180 214" stroke="#E6DCFF" strokeOpacity=".22" strokeWidth="1.2" strokeDasharray="1 5" strokeLinecap="round" />
        <path d="M252 108 Q240 180 180 214" stroke="#E6DCFF" strokeOpacity=".22" strokeWidth="1.2" strokeDasharray="1 5" strokeLinecap="round" />
        <g className={l.bobA}><circle cx="86" cy="112" r="34" fill="url(#lp-a)" /><circle cx="86" cy="112" r="42" stroke="#B9A2FF" strokeOpacity=".35" /></g>
        <g className={l.bobB}><circle cx="252" cy="108" r="26" fill="url(#lp-b)" /><ellipse cx="252" cy="108" rx="40" ry="10" stroke="#E3A6B8" strokeOpacity=".6" strokeWidth="1.4" transform="rotate(-18 252 108)" /></g>
        <g className={l.bobC}><circle cx="180" cy="214" r="22" fill="url(#lp-c)" /><circle cx="180" cy="214" r="29" stroke="#8CC8E8" strokeOpacity=".32" /></g>
        <g className={l.orbit}>
          <circle cx="171" cy="150" r="76" stroke="#FFE3B8" strokeOpacity=".2" strokeDasharray="3 7" />
          <g className={l.sat}>
            <circle cx="247" cy="150" r="11" fill="#FFE3B8" opacity=".18" />
            <circle cx="247" cy="150" r="5.5" fill="#FFE3B8" />
          </g>
        </g>
        <circle cx="44" cy="226" r="1.6" fill="#FFE3B8" opacity=".7" />
        <circle cx="306" cy="200" r="1.3" fill="#fff" opacity=".6" />
        <circle cx="150" cy="40" r="1.4" fill="#fff" opacity=".7" />
      </svg>
    </div>
  )
}
