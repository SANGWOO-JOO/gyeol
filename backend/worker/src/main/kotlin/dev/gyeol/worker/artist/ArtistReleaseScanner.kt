package dev.gyeol.worker.artist

import dev.gyeol.core.identify.ArtistReleaseRepository
import dev.gyeol.core.identify.SearchText
import dev.gyeol.core.identify.TitleNoise
import dev.gyeol.core.identify.TitleVersion
import dev.gyeol.worker.identify.FetcherClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * I-30 ③ 가수 화면 최신순: 가수의 MusicBrainz 녹음 목록을 100개씩 훑어 같은 제목은 가장 이른 "처음 나온 날" 하나로 저장한다.
 * room-song 큐의 SCAN_ARTIST_RELEASES 메시지로 돈다(새 큐를 만들지 않음, 결정 A5). MusicBrainz 초당 1회 제한은 fetcher가 지킨다.
 * 페이지마다 바로 저장하므로 RUNNING 중에도 API가 그때까지 모은 곡을 보여 줄 수 있다
 */
@Service
class ArtistReleaseScanner(private val repo: ArtistReleaseRepository, private val fetcher: FetcherClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 한 가수에 훑는 녹음 상한 (30쪽 ≈ 33초). 라이브·리믹스가 수천 개인 가수에서 끝없이 돌지 않게 */
        const val MAX_RECORDINGS = 3000
        /** 이보다 짧은 녹음은 곡이 아니라 해설·인트로 조각으로 본다 (IVE "IVE on Thank U" 35초 등, I-39) */
        const val MIN_LENGTH_MS = 60_000L
    }

    fun scan(artistId: Long) {
        if (!repo.startScan(artistId)) return // 이미 다른 메시지가 맡았거나 요청이 없음
        val started = System.nanoTime()
        var total = 0
        var skipped = 0
        try {
            val mbid = repo.artistMbid(artistId) ?: error("가수가 없습니다: $artistId")
            var offset = 0
            while (offset < MAX_RECORDINGS) {
                val page = fetcher.artistRecordings(mbid.toString(), offset)
                total = page.path("total").asInt(0)
                val items = page.path("items").toList()
                for (r in items) {
                    if (r.path("video").asBoolean(false)) continue // 뮤직비디오 녹음은 노래 목록에서 뺀다
                    val title = r.path("title").asString("").trim()
                    // I-39·I-41: 주 가수(첫 크레딧)가 이 가수가 아닌 녹음(같은 이름의 다른 가수가 잘못 연결된 경우 등),
                    // 60초 미만(해설·인트로 조각), 팬 편집·스킷·라이브 공연 사이 부분은 저장하지 않는다
                    val primary = r.path("primaryArtistMbid").asString("")
                    if (primary.isNotEmpty() && primary != mbid.toString()) { skipped++; continue }
                    val length = r.path("lengthMs").takeIf { it.isNumber }?.asLong()
                    if (length != null && length < MIN_LENGTH_MS) { skipped++; continue }
                    if (TitleNoise.isNoise(title) || TitleNoise.isLiveSegment(title)) { skipped++; continue }
                    val norm = SearchText.norm(title)
                    if (norm.isEmpty()) continue
                    val date = r.path("firstReleaseDate").asString("").ifEmpty { null }?.takeIf { it.length in setOf(4, 7, 10) }
                    repo.upsert(artistId, UUID.fromString(r.path("mbid").asString()), title, norm, date, TitleVersion.has(title))
                }
                offset += 100
                if (items.isEmpty() || offset >= total) break
            }
            repo.finishScan(artistId, true, total, repo.count(artistId))
            log.info("가수 {} 발매일 모으기: 녹음 {}개 → 곡 {}개 (거른 것 {}), {}ms", artistId, total, repo.count(artistId), skipped, (System.nanoTime() - started) / 1_000_000)
        } catch (e: Exception) {
            log.warn("가수 {} 발매일 모으기 실패: {}", artistId, e.message)
            repo.finishScan(artistId, false, total.takeIf { it > 0 }, repo.count(artistId))
        }
    }
}
