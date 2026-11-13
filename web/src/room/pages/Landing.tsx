import { useState } from 'react'
import { Link } from 'react-router'
import { keyStore } from '../keyStore'
import { NoteIcon, RoomPage } from '../components/RoomPage'
import { Wordmark } from '../components/Wordmark'
import lp from '../assets/lp.webp'
import arm from '../assets/tonearm.webp'
import s from '../room.module.css'
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
        <section className={s.note} aria-label="이 기기에서 들어간 우주">
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
        <h1 className={l.headline}>오늘 밤,<br />노래로 <span className={l.accent}>우주</span>를 그려요</h1>
        <p className={s.lead} style={{ fontSize: 15 }}>친구와 노래를 모으면, 우리만의 지도가 생겨요.</p>
      </section>

      <div className={s.section} style={{ gap: 4 }}>
        <Link to="/rooms/new" className={s.primary} style={{ height: 58 }}>
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

/** 레코드판 + 바늘 + 레코드 홈을 도는 두 사람의 노래가 금빛으로 만나는 모습 (캔버스 1번 보드) */
function Hero() {
  return (
    <div className={l.heroBox}>
      <svg viewBox="0 0 342 330" fill="none" aria-hidden="true" className={l.heroBack}>
        <circle cx="171" cy="160" r="142" fill="#1B1834" />
        <g className={l.wave}><path d="M-120 262 q30 -16 60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0" stroke="#B9A2FF" strokeWidth="1.2" opacity="0.38" /></g>
        <g className={`${l.wave} ${l.waveFast}`}><path d="M-120 276 q30 -14 60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0" stroke="#FF9EC0" strokeWidth="1" opacity="0.26" /></g>
        <circle cx="171" cy="160" r="134" stroke="#3A3466" strokeWidth="1.1" strokeDasharray="1 7" strokeLinecap="round" />
        <g className={l.orbit}>
          <circle cx="171" cy="26" r="6" fill="#FFE3B8" opacity="0.18" />
          <circle cx="171" cy="26" r="2.8" fill="#FFE3B8" />
        </g>
      </svg>
      <img src={lp} alt="" className={l.lp} />
      <img src={arm} alt="" className={l.arm} />
      <svg viewBox="0 0 342 330" fill="none" role="img" aria-label="레코드판의 홈을 따라 모인 친구들의 노래가 금빛으로 만나는 모습" className={l.heroFront}>
        <g className={`${l.anchor} ${l.lavender}`}>
          <circle cx="171" cy="54" r="4.2" fill="#B9A2FF" />
          <circle cx="171" cy="54" r="7.2" stroke="#B9A2FF" strokeWidth="1" opacity="0.34" />
        </g>
        <g className={`${l.anchor} ${l.rose}`}>
          <circle cx="171" cy="78" r="4.2" fill="#E3A6B8" />
          <circle cx="171" cy="78" r="7.2" stroke="#E3A6B8" strokeWidth="1" opacity="0.3" />
        </g>
        <g className={`${l.anchor} ${l.gold}`}>
          <circle cx="171" cy="78" r="5" fill="#FFE3B8" />
          <circle cx="171" cy="78" r="8.5" stroke="#FFE3B8" strokeWidth="1" opacity="0.42" />
        </g>
        <circle className={l.ring} cx="252" cy="149" r="10" stroke="#FFE3B8" strokeWidth="1.2" />
        <circle className={l.spark} cx="265" cy="132" r="1.8" fill="#FFE3B8" />
        <circle className={`${l.spark} ${l.sparkB}`} cx="261" cy="154" r="1.4" fill="#F5F0FF" />
        <circle className={`${l.spark} ${l.sparkC}`} cx="241" cy="157" r="1.3" fill="#FFE3B8" />
        <circle cx="126" cy="103" r="2.4" fill="#B9A2FF" opacity="0.72" />
        <circle cx="228" cy="214" r="2.4" fill="#E3A6B8" opacity="0.68" />
        <circle cx="132" cy="207" r="2" fill="#B9A2FF" opacity="0.52" />
      </svg>
    </div>
  )
}
