package dev.gyeol.worker.room

import dev.gyeol.core.identify.AliasKey
import dev.gyeol.core.identify.ArtistImageTarget
import dev.gyeol.core.identify.ArtistPrepareRepository
import dev.gyeol.core.identify.Target
import dev.gyeol.core.identify.TargetStatus
import dev.gyeol.core.listen.Source
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.artistimage.ArtistImageMessage
import dev.gyeol.worker.identify.FetcherClient
import dev.gyeol.worker.identify.IdentifyService
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 검색 때 가수 미리 준비 (11-14 B안 → I-72 인기곡 목록 + 준비 커서, 11-15).
 * 1. 인기곡 목록(Last.fm top-tracks [TOP_LIST]곡)을 artist_top_track에 저장한다. 7일 지나야 다시 받는다
 * 2. 가수 확정: 우리 가수(id: 키)는 DB mbid, 처음 보는 가수(n: 키)는 find-artist(이름·별칭 + 제목 검증, 하나로 줄지 않으면 SKIPPED)
 * 3. 커서: 창(첫 검색 10위, 더보기 20위) 안에서 아직 안 했거나 며칠 지난 실패·없음 순위를 [BATCH]곡씩 식별(LLM 없이, MusicBrainz 낮은 순위)
 *    → 확정한 가수의 곡이면 READY(표지 큐), 다른 가수 곡이면 OTHER, 없으면 NO_MATCH, 호출 실패면 FAILED. 이미 READY인 순위는 다시 부르지 않는다
 * 4. 창 안에 남은 순위가 있고 오늘 묶음이 [BATCHES_PER_DAY] 미만이면 다음 묶음을 큐에 이어서 넣는다
 * 공용 지도(PREPARE_ARTIST)에는 올리지 않는다.
 */
@Service
class ArtistPrepareService(
    private val repo: ArtistPrepareRepository,
    private val fetcher: FetcherClient,
    private val identify: IdentifyService,
    private val sqs: SqsTemplate,
    private val json: JsonMapper,
    private val props: WorkerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst = ZoneId.of("Asia/Seoul")

    fun prepare(key: String, name: String?, artistId: Long?) {
        if (!repo.start(key)) return // 이미 맡았거나 끝남 (같은 메시지를 두 번 받은 경우)
        val started = System.nanoTime()
        val today = LocalDate.now(kst)
        val outcome = try {
            batch(key, name.orEmpty(), artistId, today)
        } catch (e: RuntimeException) {
            log.warn("가수 미리 준비 실패: {} {}", key, e.message)
            Triple("FAILED", artistId, e.javaClass.simpleName)
        }
        val (status, aid, reason) = outcome
        if (status == "CONTINUE") {
            sqs.send { it.queue(props.room.songQueue).payload(json.writeValueAsString(RoomSongMessage("PREPARE_SEARCH_ARTIST", artistId = artistId, name = name, prepKey = key))) }
        } else {
            repo.finish(key, status, aid, reason)
        }
        log.info("가수 미리 준비: {} → {} (가수 {}, {}), {}ms", key, status, aid, reason ?: "-", (System.nanoTime() - started) / 1_000_000)
    }

    /** 묶음 하나. (CONTINUE면 다음 묶음을 이어 맡김 | DONE | SKIPPED, 가수 id, 이유) */
    private fun batch(key: String, name: String, artistId: Long?, today: LocalDate): Triple<String, Long?, String?> {
        val st = repo.state(key, today) ?: return Triple("FAILED", artistId, "NO_STATE")
        // 1. 인기곡 목록 (7일 안에 받은 목록이면 Last.fm을 부르지 않는다)
        if (!repo.topFresh(key)) {
            if (name.isBlank()) return Triple("SKIPPED", artistId, "NO_NAME")
            val titles = fetcher.lastfm("top-tracks", mapOf("artist" to name, "limit" to TOP_LIST))
                .path("items").toList().mapNotNull { it.path("title").asString("").ifBlank { null } }.distinctBy { it.lowercase() }
            if (titles.isNotEmpty()) repo.saveTopTracks(key, titles)
            if (titles.isEmpty() && repo.topTracks(key).isEmpty()) {
                if (artistId == null) return Triple("SKIPPED", null, "NO_TOP_TRACKS")
                // 우리 가수인데 Last.fm 목록이 없으면: 표지 미확인 곡과 사진만 (예전 id: 키 동작)
                repo.uncheckedCoverTracks(artistId, BATCH).takeIf { it.isNotEmpty() }?.let(identify::requestCovers)
                repo.artistMbid(artistId)?.let { m -> if (repo.needsPhoto(artistId, props.artistImage.retry.toDays())) photoQueue(artistId, m) }
                repo.countBatch(key, today)
                return Triple("DONE", artistId, "NO_TOP_TRACKS")
            }
        }
        val top = repo.topTracks(key)
        // 2. 가수 확정 (한 번 확정하면 저장해 두고 다시 묻지 않는다)
        val mbid = artistId?.let(repo::artistMbid) ?: st.artistMbid
            ?: fetcher.findArtist(name, top.take(TITLES).map { it.title }, lowPriority = true)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Triple("SKIPPED", artistId, "AMBIGUOUS") // "Rain"처럼 이름 같은 가수가 여럿이면 준비하지 않는다
        if (st.artistMbid == null) repo.setArtistMbid(key, mbid)
        // 우리 가수의 첫 묶음: 표지를 확인한 적 없는 곡도 표지 큐로 (예전 id: 키 동작)
        if (artistId != null && st.batchesToday == 0) repo.uncheckedCoverTracks(artistId, BATCH).takeIf { it.isNotEmpty() }?.let(identify::requestCovers)

        // 3. 커서: 이번 묶음
        val channel = "$name - Topic" // 방 담기와 같은 키: 검색 줄(TrackMerger)이 이 키로 우리 곡에 이어진다
        var ready = 0
        var resolvedArtist = artistId
        // 묶음 안 곡들은 동시에 식별한다: MusicBrainz 응답이 느려(호출당 1~2초) 한 곡씩이면 초당 1회 예산의 절반도 못 쓴다.
        // 속도 제한은 fetcher의 RateGate가 지키므로 동시에 보내도 초당 1회를 넘지 않는다 (I-72)
        val ranks = repo.nextRanks(key, st.windowEnd, BATCH)
        // 끝나는 대로 표시한다: 느린 곡(못 찾음) 때문에 빨리 찾은 곡의 표지가 늦지 않게
        val done = java.util.concurrent.ExecutorCompletionService<Pair<ArtistPrepareRepository.TopTrack, Pair<String, Long?>>>(pool)
        ranks.forEach { r -> done.submit { r to identifyRank(key, r, channel, mbid) } }
        repeat(ranks.size) {
            val (r, res) = done.take().get()
            val (status, trackId) = res
            repo.markRank(key, r.rank, status, trackId.takeIf { status == "READY" })
            if (status == "READY") {
                ready++
                identify.requestCovers(listOf(trackId!!))
                if (resolvedArtist == null) resolvedArtist = repo.trackArtistId(trackId)
            }
        }
        repo.countBatch(key, today)
        // 처음 보는 가수가 우리 가수가 됐으면: 사진 큐, 목록을 id: 키에도 복사(다음 검색 카드는 id: 키)
        if (resolvedArtist != null) {
            if (repo.needsPhoto(resolvedArtist, props.artistImage.retry.toDays())) photoQueue(resolvedArtist, mbid)
            if (key.startsWith("n:")) repo.copyTopTracks(key, "id:$resolvedArtist")
        }
        // 4. 이어서 맡길지
        val more = repo.nextRanks(key, st.windowEnd, 1).isNotEmpty()
        return if (more && st.batchesToday + 1 < BATCHES_PER_DAY && repo.requeue(key)) Triple("CONTINUE", resolvedArtist, null)
        else Triple("DONE", resolvedArtist, if (top.isEmpty()) "NO_TOP_TRACKS" else null)
    }

    /** 순위 하나 식별 (LLM 없이, MusicBrainz 낮은 순위). (상태, 곡 id) */
    private fun identifyRank(key: String, r: ArtistPrepareRepository.TopTrack, channel: String, mbid: UUID): Pair<String, Long?> {
        var trackId: Long? = null
        val target = Target(0, AliasKey.of(r.title, channel), r.title, channel, Source.YOUTUBE, 1, TargetStatus.PENDING)
        return try {
            identify.identifyTargets(listOf(target), object : IdentifyService.TargetSink {
                override fun finish(t: Target, trackId: Long?) { if (trackId != null) setTrack(trackId) }
                override fun fail(t: Target) {}
                private fun setTrack(id: Long) { trackId = id }
            }, allowLlm = false, background = true)
            val tid = trackId
            when {
                tid == null -> "NO_MATCH" to null
                repo.trackArtistMbid(tid) == mbid -> "READY" to tid // 동명이인 곡이 섞이지 않게: 확정한 가수의 곡만
                else -> "OTHER" to tid
            }
        } catch (e: RuntimeException) {
            log.warn("가수 미리 준비 곡 실패: {} {}위 {}: {}", key, r.rank, r.title, e.message)
            "FAILED" to null
        }
    }

    private val pool = java.util.concurrent.Executors.newFixedThreadPool(BATCH)

    @jakarta.annotation.PreDestroy
    fun close() { pool.shutdownNow() }

    private fun photoQueue(artistId: Long, mbid: UUID) {
        sqs.send { it.queue(props.artistImage.queue).payload(json.writeValueAsString(ArtistImageMessage(items = listOf(ArtistImageTarget(artistId, mbid.toString(), null)), commonsOnly = true))) }
    }

    companion object {
        /** 한 묶음에 준비할 곡 수 */
        const val BATCH = 3
        /** 받아 둘 인기곡 수 (검색 1~5쪽 = 50곡) */
        const val TOP_LIST = 50
        /** find-artist 검증에 쓰는 제목 수 (fetcher 상한 3) */
        const val TITLES = 3
        /** 가수 키당 하루 묶음 상한 (20위까지 = 7묶음 + 여유) */
        const val BATCHES_PER_DAY = 10
    }
}
