// 큰 파일(실제 50MB 안팎)을 읽고 거르는 동안 화면이 멈추지 않도록 Web Worker에서 처리한다
import { filterTakeout, TakeoutFormatError, type FilterResult, type UploadItem } from './filter'

export type WorkerResponse =
  | { ok: true; result: Omit<FilterResult, 'items' | 'unclassifiedItems'>; items: UploadItem[]; unclassifiedItems: UploadItem[]; ms: number }
  | { ok: false; message: string }

self.onmessage = async (e: MessageEvent<File>) => {
  const started = performance.now()
  try {
    let data: unknown
    try {
      data = JSON.parse(await e.data.text())
    } catch {
      throw new TakeoutFormatError('JSON 파일이 아닙니다. Takeout에서 형식을 JSON으로 골라 내보낸 파일인지 확인해 주세요.')
    }
    // 업로드 파일은 음악 판정(L2-3b) 뒤에 화면 쪽에서 만든다
    const { items, unclassifiedItems, ...result } = filterTakeout(data)
    self.postMessage({ ok: true, result, items, unclassifiedItems, ms: Math.round(performance.now() - started) } satisfies WorkerResponse)
  } catch (err) {
    const message = err instanceof TakeoutFormatError ? err.message : '파일을 읽지 못했습니다.'
    self.postMessage({ ok: false, message } satisfies WorkerResponse)
  }
}
