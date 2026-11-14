// I-14 방 그림(표지 저장본·가수 사진) 받기: 후보 주소를 차례로 시도한다.
import { useCallback, useEffect, useState } from 'react'
import { roomPhoto } from './roomApi'

/**
 * 그림 후보 하나.
 * - cookie: 방 주소를 <img>/<image>에 바로 (참가할 때 받은 HttpOnly 쿠키로 통과, 가볍고 브라우저 캐시를 그대로 씀)
 * - key: 같은 주소를 열쇠 헤더로 받아 blob (쿠키가 안 남는 브라우저 대비)
 * - plain: 외부 주소(coverartarchive 등)를 바로
 */
export type ImgSource = { url: string; mode: 'cookie' | 'key' | 'plain' }

export const isRoomPath = (u: string) => u.startsWith('/api/rooms/')

/** 주소들 → 후보 (빈 값·중복은 뺀다). 방 주소는 쿠키 먼저, 안 되면 열쇠 blob */
export function sourcesOf(urls: (string | null | undefined)[]): ImgSource[] {
  const seen = new Set<string>()
  const out: ImgSource[] = []
  for (const u of urls) {
    if (!u || seen.has(u)) continue
    seen.add(u)
    if (isRoomPath(u)) out.push({ url: u, mode: 'cookie' }, { url: u, mode: 'key' })
    else out.push({ url: u, mode: 'plain' })
  }
  return out
}

/** 곡 표지: 저장본(coverPath) → 외부 주소(coverUrl) */
export const coverSources = (c: { coverPath?: string | null; coverUrl?: string | null }) => sourcesOf([c.coverPath, c.coverUrl])

export const sourcesKey = (s: ImgSource[]) => s.map(x => `${x.mode}:${x.url}`).join('|')

/**
 * 이 방 쿠키가 이 브라우저에 있는지 한 번만 알아본다 (방마다 요청 하나).
 * 그림마다 쿠키로 먼저 해 보고 401이면 열쇠로 다시 받으면, 쿠키 없는 브라우저에선 요청이 두 배가 된다.
 */
const cookieProbe = new Map<string, Promise<boolean>>()
export function roomCookieWorks(roomId: string): Promise<boolean> {
  let p = cookieProbe.get(roomId)
  if (!p) {
    p = fetch(`/api/rooms/${encodeURIComponent(roomId)}/me`, { credentials: 'same-origin' }).then(r => r.ok).catch(() => false)
    cookieProbe.set(roomId, p)
  }
  return p
}

/** 다음에 시도할 후보 번호: 쿠키가 되면 열쇠 단계를, 안 되면 쿠키 단계를 건너뛴다 (방 주소마다 둘 중 하나만) */
export function nextIndex(sources: ImgSource[], from: number, cookieOk: boolean): number {
  let i = from
  while (i < sources.length && sources[i].mode === (cookieOk ? 'key' : 'cookie')) i++
  return i
}

/**
 * 후보를 차례로(방 주소는 쿠키 또는 열쇠 중 되는 쪽 하나만): src가 undefined면 받는 중, null이면 모두 실패(부모가 지금의 대체를 그린다).
 * 그림 요소의 onError에 fail을 걸면 다음 후보로 넘어간다. 같은 주소의 blob은 roomPhoto가 한 번만 받는다.
 */
export function useImageChain(roomId: string, sources: ImgSource[]): { src: string | null | undefined; fail: () => void } {
  const key = `${roomId}#${sourcesKey(sources)}`
  const [st, setSt] = useState<{ k: string; i: number; src: string | null | undefined }>({ k: key, i: 0, src: undefined })
  const cur = st.k === key ? st : { k: key, i: 0, src: undefined }

  useEffect(() => {
    if (cur.src !== undefined) return
    let alive = true
    const set = (i: number, src: string | null) => { if (alive) setSt({ k: key, i, src }) }
    const step = async (from: number) => {
      const needsRoom = sources.slice(from).some(x => x.mode !== 'plain')
      const cookieOk = needsRoom ? await roomCookieWorks(roomId) : false
      if (!alive) return
      const i = nextIndex(sources, from, cookieOk)
      if (i >= sources.length) return set(i, null)
      const s = sources[i]
      if (s.mode !== 'key') return set(i, s.url)
      const u = await roomPhoto(roomId, s.url)
      if (!alive) return
      if (u) set(i, u)
      else step(i + 1)
    }
    step(cur.i)
    return () => { alive = false }
    // sources 내용은 key에 들어 있다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, cur.i, cur.src === undefined])

  const fail = useCallback(() => setSt(p => ({ k: key, i: (p.k === key ? p.i : 0) + 1, src: undefined })), [key])
  return { src: cur.src, fail }
}
