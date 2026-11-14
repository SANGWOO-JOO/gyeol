// I-44 가수 행성 얼굴 (캔버스 4-n·4-n2). 얼굴 고르기와 장르(구역) 색은 화면과 떨어뜨려 테스트한다.
import type { Face } from '../roomApi'
import { sourcesOf, type ImgSource } from '../images'

/**
 * 장르 계열: 구역 이름의 낱말로 고른다(구역 id 해시가 아니라). 위에서부터 먼저 맞는 계열.
 * 계열마다 색상(hue)이 뚜렷이 다르고, 같은 계열 안의 구역은 밝기 세 단계로만 구분한다 → 비슷한 장르는 같은 계열 색.
 */
export interface GenreFamily { key: string; hue: number; sat: number; words: string[] }
export const GENRE_FAMILIES: GenreFamily[] = [
  { key: 'hiphop', hue: 42, sat: 88, words: ['힙합', '랩', '래퍼', '래프', '트랩', '드릴'] },
  { key: 'ballad', hue: 258, sat: 90, words: ['발라드'] },
  { key: 'soul', hue: 18, sat: 92, words: ['R&B', '소울'] },
  { key: 'electronic', hue: 212, sat: 88, words: ['전자', '일렉트로', '신스', '트랜스', '디지털', '보컬로이드', '포크트로니카'] },
  { key: 'heavy', hue: 352, sat: 78, words: ['메탈', '펑크', '하드코어'] },
  { key: 'rock', hue: 6, sat: 72, words: ['록'] },
  { key: 'indie', hue: 168, sat: 52, words: ['인디', '포크', '싱어송', '침실', '기타'] },
  { key: 'dance', hue: 332, sat: 90, words: ['걸그룹', '보이밴드', '아이돌', '댄스', '댄서', '유닛'] },
  { key: 'pop', hue: 288, sat: 70, words: ['팝', '보컬', '앙상블'] },
]
const OTHER = { key: 'other', hue: 252, sat: 18 }

export function genreFamily(name: string | null | undefined): { key: string; hue: number; sat: number } {
  const n = name ?? ''
  return GENRE_FAMILIES.find(f => f.words.some(w => n.includes(w))) ?? OTHER
}

/** 구역 색: 계열 색상 + 구역 id로 밝기 세 단계(68·76·61%). 구역 없음·이름 없음은 회보라 */
export function genreColor(name: string | null | undefined, galaxyId: number | null | undefined): string {
  const f = genreFamily(name)
  const light = [68, 76, 61][Math.abs(galaxyId ?? 0) % 3]
  return `hsl(${f.hue} ${f.sat}% ${light}%)`
}

/**
 * 행성 얼굴 대체 순서 (I-44·I-14): 가수 사진(PHOTO) → 앨범 표지(서버 COVER, 없으면 방에 담긴 곡 표지: 저장본 → 외부 주소) → 장르색(글자 없음).
 * 사진·저장본은 못 받을 수 있어(401·404) 다음 후보를 같이 넘긴다.
 */
export type FaceChoice = { kind: 'photo' | 'cover'; sources: ImgSource[] } | { kind: 'genre' }

export function planetFace(p: { songs: { coverUrl: string | null; coverPath?: string | null }[]; face?: Face | null }): FaceChoice {
  const f = p.face
  const song = p.songs.find(s => s.coverPath || s.coverUrl)
  const covers = [f?.kind === 'COVER' ? f.url : null, song?.coverPath, song?.coverUrl]
  if (f?.kind === 'PHOTO' && f.url) return { kind: 'photo', sources: sourcesOf([f.url, ...covers]) }
  const sources = sourcesOf(covers)
  return sources.length ? { kind: 'cover', sources } : { kind: 'genre' }
}

/** 사진 출처 줄 (4-n4): "사진: 작성자 · 라이선스 · Wikimedia Commons", 빈 칸은 뺀다. 출처가 없으면 null */
export function photoCreditText(c: { author?: string | null; license?: string | null } | null | undefined): string | null {
  if (!c) return null
  const clean = (v: string | null | undefined) => v?.replace(/\s+/g, ' ').trim()
  return '사진: ' + [clean(c.author), clean(c.license), 'Wikimedia Commons'].filter(Boolean).join(' · ')
}

/** 알약 이름표 폭(px): 글자 폭 추정 + 좌우 여백 */
export function pillWidth(name: string, fontPx: number): number {
  let w = 0
  for (const ch of name) w += /[\u0000-ÿ]/.test(ch) ? fontPx * 0.58 : fontPx * 0.98
  return Math.round(w + fontPx * 1.3)
}
