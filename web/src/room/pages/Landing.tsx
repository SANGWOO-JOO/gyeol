import { useId, useState } from 'react'
import { Link } from 'react-router'
import { keyStore } from '../keyStore'
import { RoomPage } from '../components/RoomPage'
import { Wordmark } from '../components/Wordmark'
import s from '../room.module.css'
import o from '../components/outside.module.css'
import l from './Landing.module.css'

/** 1 첫 화면: 방 만들기 중심. 청취 기록(/upload)·곡 고르기(/pick) 링크는 11-15 사용자 요청으로 숨김 (주소로는 그대로 열림) */
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

      <section className={l.copy}>
        <h1 className={l.headline}>네 노래와 내 노래로<br /><span className={l.emphasis}>우리 우주</span>를 그려요</h1>
        <p className={l.lead}>한 곡씩 모인 취향이 행성이 되어,<br />우리만의 지도로 이어져요.</p>
      </section>

      <div className={l.action}>
        <Link to="/rooms/new" className={`${s.primary} ${o.cta} ${l.cta}`}>
          <CosmosNoteIcon /> 친구랑 우주 그리기
        </Link>
      </div>
    </RoomPage>
  )
}

/** 각자의 행성이 함께 고른 노래를 중심으로 연결되는 작은 취향 지도. */
function Hero() {
  const prefix = `lp-${useId().replace(/:/g, '')}`
  const id = (name: string) => `${prefix}-${name}`
  const paint = (name: string) => `url(#${id(name)})`

  return (
    <div className={l.heroBox}>
      <svg viewBox="0 0 360 330" fill="none" role="img" aria-label="보라, 분홍, 하늘빛 친구 행성이 함께 담은 플레이리스트를 듣고, 음표와 별이 흐르는 궤도로 이어진 우주" className={l.heroSvg}>
        <defs>
          <linearGradient id={id('lavender')} x1="57" y1="90" x2="137" y2="175" gradientUnits="userSpaceOnUse"><stop stopColor="#E6DCFF" /><stop offset="1" stopColor="#A28AD9" /></linearGradient>
          <linearGradient id={id('pink')} x1="244" y1="108" x2="292" y2="176" gradientUnits="userSpaceOnUse"><stop stopColor="#FFE0EA" /><stop offset="1" stopColor="#D79BB2" /></linearGradient>
          <linearGradient id={id('sky')} x1="169" y1="235" x2="214" y2="290" gradientUnits="userSpaceOnUse"><stop stopColor="#CEEFFF" /><stop offset="1" stopColor="#81B9D4" /></linearGradient>
          <radialGradient id={id('haze')}><stop stopColor="#B9A2FF" stopOpacity=".16" /><stop offset="1" stopColor="#B9A2FF" stopOpacity="0" /></radialGradient>
          <filter id={id('grain')} x="0" y="0" width="100%" height="100%"><feTurbulence type="fractalNoise" baseFrequency=".85" numOctaves="3" stitchTiles="stitch" /><feColorMatrix type="saturate" values="0" /><feComponentTransfer><feFuncA type="linear" slope=".18" /></feComponentTransfer><feComposite in2="SourceGraphic" operator="in" /><feBlend in="SourceGraphic" mode="soft-light" /></filter>
        </defs>
        <ellipse cx="180" cy="180" rx="174" ry="149" fill={paint('haze')} />
        <g strokeLinecap="round" strokeLinejoin="round">
          <path d="M42 189C-7 240 87 265 204 225S359 117 309 110" stroke="#E6DCFF" strokeOpacity=".3" />
          <path d="M37 213C54 254 197 235 280 177C309 157 327 134 322 121" stroke="#B9A2FF" strokeOpacity=".2" />
          <path className={l.orbitFlow} d="M100 132Q174 48 270 145Q270 228 192 263Q109 222 100 132Z" stroke="#E6DCFF" strokeOpacity=".32" strokeDasharray="2 7" />
          <path className={l.orbitLinks} d="M128 151Q162 167 174 183M247 164Q222 173 211 187M190 215L192 237" stroke="#FFE3B8" strokeOpacity=".45" strokeDasharray="2 5" />
        </g>
        <g className={l.bobA}>
          <ellipse cx="95" cy="132" rx="67" ry="19" transform="rotate(-23 95 132)" stroke="#B9A2FF" strokeWidth="2" strokeOpacity=".65" />
          <path d="M49 128C47 101 67 82 96 85C125 83 145 108 141 135C141 164 119 181 93 179C65 180 47 158 49 128Z" fill={paint('lavender')} stroke="#E6DCFF" strokeOpacity=".65" filter={paint('grain')} />
          <path d="M61 111Q70 95 88 96M60 151Q76 163 90 161" stroke="#F5EEFF" strokeOpacity=".5" strokeWidth="2" strokeLinecap="round" />
          <g stroke="#665185" strokeWidth="1.8" strokeLinecap="round"><g className={l.blinkA}><path d="M87 124L87 127M105 123L105 126" /></g><path d="M92 134Q97 139 102 133" /></g>
          <g fill="#E3A6B8" opacity=".65"><ellipse cx="79" cy="134" rx="6" ry="3" /><ellipse cx="114" cy="132" rx="6" ry="3" /></g>
          <path d="M34 151C40 169 126 146 153 116" stroke="#C9B5FF" strokeWidth="4" strokeLinecap="round" /><path className={l.ringShimmer} d="M37 152C56 161 119 142 146 122" stroke="#F0E7FF" strokeWidth="1.2" strokeLinecap="round" />
          <path d="M131 146L133 151L139 152L134 155L134 160L130 156L125 157L128 152Z" fill="#FFE3B8" />
        </g>
        <g className={l.bobB}>
          <path d="M234 141C232 119 247 103 269 104C292 103 307 121 305 144C305 165 288 180 267 178C247 180 232 162 234 141Z" fill={paint('pink')} stroke="#FFE0EA" strokeOpacity=".6" filter={paint('grain')} />
          <path d="M245 127Q250 116 263 115" stroke="#FFF0F4" strokeOpacity=".65" strokeWidth="2" strokeLinecap="round" />
          <g stroke="#8F5E77" strokeWidth="1.7" strokeLinecap="round"><g className={l.blinkB}><path d="M254 140Q257 136 260 140M276 140Q279 136 282 140" /></g><path d="M264 149Q268 152 272 148" /></g>
          <g fill="#D487A5" opacity=".5"><ellipse cx="250" cy="148" rx="5" ry="2.8" /><ellipse cx="286" cy="148" rx="5" ry="2.8" /></g>
          <path className={l.heartPulse} d="M280 118C276 112 271 118 280 123C289 118 284 112 280 118Z" fill="#FFF0F4" />
        </g>
        <g className={l.bobC}>
          <path d="M158 263C156 243 171 228 191 230C211 228 227 245 225 264C226 284 209 297 190 296C171 297 157 282 158 263Z" fill={paint('sky')} stroke="#D8F4FF" strokeOpacity=".65" filter={paint('grain')} />
          <path d="M165 250H180Q183 240 190 246Q196 245 198 250H207" stroke="#ECFAFF" strokeOpacity=".65" strokeWidth="4" strokeLinecap="round" />
          <g stroke="#4B7E99" strokeWidth="1.7" strokeLinecap="round"><g className={l.blinkC}><path d="M181 264V267M200 264V267" /></g><path d="M187 273Q191 277 195 273" /></g>
          <g fill="#E3A6B8" opacity=".6"><ellipse cx="174" cy="272" rx="5" ry="2.5" /><ellipse cx="207" cy="272" rx="5" ry="2.5" /></g>
          <path d="M169 289Q192 299 214 285" stroke="#D8F4FF" strokeOpacity=".5" strokeLinecap="round" />
        </g>
        {/* A little handmade playlist sleeve ties the three friends together. */}
        <g className={l.playlistBob}><g transform="rotate(-9 190 195)">
          <rect x="167" y="172" width="47" height="45" rx="9" fill="#29213F" stroke="#E6DCFF" strokeOpacity=".55" />
          <rect x="173" y="178" width="16" height="18" rx="4" fill="#E3A6B8" />
          <path d="M181 181V189M181 183L185 182" stroke="#4D355C" strokeWidth="1.4" strokeLinecap="round" /><ellipse cx="179" cy="190" rx="2.3" ry="1.8" fill="#4D355C" />
          <path d="M195 182H207M195 187H203M175 204H206" stroke="#E6DCFF" strokeOpacity=".65" strokeWidth="1.5" strokeLinecap="round" /><path className={l.progressFill} d="M175 204H190.5" stroke="#FFE3B8" strokeWidth="1.5" strokeLinecap="round" /><circle cx="185" cy="204" r="2.5" fill="#FFE3B8" />
          <path d="M174 168Q182 164 188 167" stroke="#FFE3B8" strokeOpacity=".65" strokeLinecap="round" />
        </g>
        </g>
        <g stroke="#FFE3B8" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <g className={l.noteA}><path d="M176 94V80L187 77V90M176 83L187 80" /><ellipse cx="173" cy="95" rx="3" ry="2.2" fill="#FFE3B8" /><ellipse cx="184" cy="91" rx="3" ry="2.2" fill="#FFE3B8" /></g>
          <g className={l.noteB}><path d="M294 211V199L301 201" /><ellipse cx="291" cy="212" rx="3" ry="2" fill="#FFE3B8" /></g>
          <g className={l.noteC}><path d="M90 237V228L96 226" stroke="#E3A6B8" /><ellipse cx="87" cy="238" rx="2.5" ry="1.8" fill="#E3A6B8" stroke="#E3A6B8" /></g>
        </g>
        <path className={l.moonGlow} d="M265 48C249 51 246 70 260 77C270 82 280 76 282 69C266 77 255 60 265 48Z" fill="#FFE3B8" fillOpacity=".85" />
        <g className={l.twinkle} fill="#E6DCFF">
          <path d="M43 69Q44 78 50 79Q43 80 42 88Q41 80 35 79Q42 77 43 69ZM314 259Q315 265 321 266Q315 267 314 274Q313 267 307 266Q313 265 314 259Z" />
          <circle cx="128" cy="54" r="1.5" /><circle cx="224" cy="107" r="1.5" /><circle cx="55" cy="278" r="1" />
        </g>
        <g className={l.twinkleLate} stroke="#FFE3B8" strokeLinecap="round">
          <path d="M122 210V218M118 214H126M320 85V91M317 88H323M135 296V302M132 299H138" />
          <circle cx="211" cy="44" r="1" /><circle cx="331" cy="188" r="1" />
        </g>
      </svg>
    </div>
  )
}

function CosmosNoteIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M8 15V6L18 4V13M8 8L18 6" />
      <circle cx="5.5" cy="16" r="3" /><circle cx="15.5" cy="14" r="3" />
      <path d="M2 18C3 20 8 18 9 15M12 16C14 18 18 16 19 13M20 19V23M18 21H22" />
    </svg>
  )
}
