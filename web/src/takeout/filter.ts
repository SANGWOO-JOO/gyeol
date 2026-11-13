// Google Takeout YouTube 기록(JSON)을 브라우저 안에서 거른다.
// - 시청 항목만 남긴다. 검색 기록·기타 활동은 버리고 서버로 보내지 않는다
// - 음악(뮤직 앱 또는 "- Topic" 채널)만 제목을 보낸다. 나머지 시청은 건수만 센다 (일반 영상 제목은 서버로 보내지 않음)
// - 파일 이름은 언어·OS마다 달라서 내용 구조로 판별한다

export type Source = 'MUSIC_APP' | 'YOUTUBE'

export interface UploadItem {
  videoId: string | null
  title: string
  channel: string | null
  watchedAt: string
  source: Source
}

export interface FilterResult {
  /** 시청 항목 전체 (삭제·비공개 포함) */
  total: number
  music: number
  /** 음악으로 판정하지 못한 시청 (L1에서는 보내지 않음) */
  unclassified: number
  /** 삭제·비공개 등으로 제목이나 채널이 없는 시청 */
  removed: number
  /** 시청이 아닌 활동 (검색, 목록 확인 등) */
  otherActivity: number
  periodStart: string | null
  periodEnd: string | null
  items: UploadItem[]
  /** 음악으로 판정하지 못한 시청 (브라우저 안에만 둔다). L2-3b에서 영상 ID로 음악 판정을 받은 것만 업로드에 더한다 */
  unclassifiedItems: UploadItem[]
}

export class TakeoutFormatError extends Error {}

interface RawSubtitle { name?: string; url?: string }
interface RawItem {
  header?: string
  title?: string
  titleUrl?: string
  subtitles?: RawSubtitle[]
  time?: string
}

// 시청 문구. 한국어는 "{제목} 을(를) 시청했습니다.", 영어는 "Watched {제목}"
const WATCH_KO = /\s*을\(를\) 시청했습니다\.?$/
const WATCH_EN = /^Watched\s+/
const SEARCH = /을\(를\) 검색했습니다\.?$|^Searched for\s/

export function watchedTitle(raw: string): string | null {
  if (WATCH_KO.test(raw)) return raw.replace(WATCH_KO, '').trim()
  if (WATCH_EN.test(raw)) return raw.replace(WATCH_EN, '').trim()
  return null
}

function videoIdOf(url: string | undefined): string | null {
  if (!url) return null
  const m = /[?&]v=([\w-]{6,})/.exec(url)
  return m ? m[1] : null
}

export function filterTakeout(data: unknown): FilterResult {
  if (!Array.isArray(data)) throw new TakeoutFormatError('YouTube 기록 파일이 아닙니다. Takeout의 "내 활동 → YouTube" JSON 파일을 골라 주세요.')
  const sample = data.slice(0, 50) as RawItem[]
  if (data.length > 0 && !sample.some(x => x && typeof x === 'object' && typeof x.title === 'string' && typeof x.time === 'string')) {
    throw new TakeoutFormatError('YouTube 기록 파일이 아닙니다. Takeout의 "내 활동 → YouTube" JSON 파일을 골라 주세요.')
  }

  const items: UploadItem[] = []
  const unclassifiedItems: UploadItem[] = []
  let total = 0, unclassified = 0, removed = 0, otherActivity = 0, searches = 0
  let start: string | null = null, end: string | null = null

  for (const x of data as RawItem[]) {
    if (!x || typeof x.title !== 'string' || typeof x.time !== 'string') { otherActivity++; continue }
    const title = watchedTitle(x.title)
    if (title === null) {
      if (SEARCH.test(x.title)) searches++
      otherActivity++
      continue
    }
    total++
    const channel = x.subtitles?.[0]?.name?.trim() || null
    // 삭제·비공개 영상: 채널이 없거나 제목 자리에 주소만 남음
    if (!channel || !title || /^https?:\/\//.test(title)) { removed++; continue }

    const musicApp = x.header === 'YouTube Music' || (x.titleUrl ?? '').includes('music.youtube.com')
    const topic = channel.endsWith(' - Topic')
    if (!musicApp && !topic) {
      unclassified++
      unclassifiedItems.push({ videoId: videoIdOf(x.titleUrl), title, channel, watchedAt: x.time, source: 'YOUTUBE' })
      continue
    }

    items.push({ videoId: videoIdOf(x.titleUrl), title, channel, watchedAt: x.time, source: musicApp ? 'MUSIC_APP' : 'YOUTUBE' })
    if (start === null || x.time < start) start = x.time
    if (end === null || x.time > end) end = x.time
  }

  if (total === 0 && searches > 0) {
    throw new TakeoutFormatError('검색 기록 파일입니다. 같은 폴더의 시청 기록이 들어 있는 파일을 골라 주세요.')
  }
  return { total, music: items.length, unclassified, removed, otherActivity, periodStart: start, periodEnd: end, items, unclassifiedItems }
}

/** 서버로 보내는 파일 (parse 워커의 UploadFile과 같은 형식) */
export function uploadBody(items: UploadItem[]): string {
  return JSON.stringify({ schemaVersion: 1, items })
}

export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024

/** 음악 판정을 맡길 영상 수 (L2-3b). 식별은 재생 상위 곡만 하므로 미분류도 재생 상위 영상만 확인한다 */
export const VIDEO_CHECK_LIMIT = 1000

/** 미분류 시청을 영상 ID별로 세어 재생 순 상위 [limit]개 ID */
export function topVideoIds(items: UploadItem[], limit = VIDEO_CHECK_LIMIT): string[] {
  const plays = new Map<string, number>()
  for (const it of items) if (it.videoId) plays.set(it.videoId, (plays.get(it.videoId) ?? 0) + 1)
  return [...plays.entries()].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)).slice(0, limit).map(([id]) => id)
}
