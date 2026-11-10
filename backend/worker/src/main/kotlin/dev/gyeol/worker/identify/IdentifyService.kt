package dev.gyeol.worker.identify

import dev.gyeol.core.identify.AliasMethod
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.MedleyRepository
import dev.gyeol.core.identify.MedleySong
import dev.gyeol.core.identify.Query
import dev.gyeol.core.identify.RuleIdentifier
import dev.gyeol.core.identify.Target
import dev.gyeol.core.identify.TargetStatus
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.enrich.EnrichTrigger
import dev.gyeol.worker.llm.CleanedTitle
import dev.gyeol.worker.llm.CleanedValidator
import dev.gyeol.worker.llm.RawTitle
import dev.gyeol.worker.llm.SpellResult
import dev.gyeol.worker.llm.Spelling
import dev.gyeol.worker.llm.TitleCleaner
import dev.gyeol.worker.llm.Version
import io.awspring.cloud.sqs.operations.SqsTemplate
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.HttpClientErrorException
import tools.jackson.databind.json.JsonMapper

/**
 * 식별 묶음 하나를 처리한다. 곡마다: 캐시 다시 확인 → 규칙으로 (가수, 곡) → fetcher(MusicBrainz) → 저장.
 * 규칙으로 못 푸는 일반 영상 제목은 묶음 안에서 한 번에 LLM으로 정리한다 (L2-3a).
 * fetcher 장애는 예외로 던져 메시지를 다시 받는다. 이미 끝난 곡은 건너뛰므로 다시 받아도 결과가 같다.
 */
@Service
class IdentifyService(
    private val repo: IdentifyRepository,
    private val fetcher: FetcherClient,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
    private val sqs: SqsTemplate,
    private val json: JsonMapper,
    private val cleaner: TitleCleaner,
    private val llmCalls: LlmCallRepository,
    private val enrichTrigger: EnrichTrigger,
    private val medleys: MedleyRepository,
) {
    private companion object {
        val IDENTIFIABLE = setOf(Version.ORIGINAL, Version.LIVE, Version.REMIX)
        const val MAX_RESPELL_TRIES = 4
        const val SAME_LENGTH_MS = 5_000L
    }

    private val log = LoggerFactory.getLogger(javaClass)

    fun process(msg: IdentifyMessage) {
        val started = System.nanoTime()
        val retryBefore = Instant.now().minus(props.identify.notFoundRetry)
        val found = mutableListOf<Long>()
        val forLlm = mutableListOf<Target>()
        val toRespell = mutableListOf<Respell>()
        for (t in repo.targets(msg.sessionId, msg.keys).filter { it.status == TargetStatus.PENDING }) {
            // 다른 세션이 그사이 같은 곡을 찾았을 수 있다
            val cached = repo.findAlias(t.key)?.takeIf { it.isFresh(retryBefore) }
            if (cached != null) {
                finish(t, cached.trackId); cached.trackId?.let(found::add); continue
            }
            val query = RuleIdentifier.query(t.title, t.channel, t.source)
            if (query == null) {
                // 공식 채널의 단순한 제목은 LLM 없이 먼저 찾아 본다. 못 찾거나 거절되면 LLM으로 (L2-3a)
                val official = RuleIdentifier.officialChannel(t.title, t.channel)
                val hit = official?.let { q -> runCatching4xx { fetcher.identify(q.artist, q.title) } }
                if (official == null || hit == null) { forLlm += t; continue }
                val id = tx.execute { repo.upsertTrack(hit) }!!
                tx.executeWithoutResult { repo.saveAlias(t.key, id, AliasMethod.RULE, official) }
                finish(t, id); found += id
                continue
            }
            val info = try {
                fetcher.identify(query.artist, query.title)
            } catch (e: HttpClientErrorException) {
                failOne(t, e); continue
            }
            // Topic 곡을 못 찾으면 다른 표기로 한 번 더 찾는다 (묶음 끝에서 LLM을 한 번만 부른다)
            // Topic 곡을 못 찾았거나, 발매일 없는 차선 음반으로만 찾았으면(로마자 일본어 제목 등) 다른 표기로 원곡을 한 번 더 찾는다
            if ((info == null || info.releaseDate == null) && t.channel?.endsWith(" - Topic") == true) { toRespell += Respell(t, query, info); continue }
            val trackId = info?.let { found -> tx.execute { repo.upsertTrack(found) } }
            tx.executeWithoutResult { repo.saveAlias(t.key, trackId, AliasMethod.RULE, query, reason = if (trackId == null) "NOT_IN_MUSICBRAINZ" else null) }
            finish(t, trackId); trackId?.let(found::add)
        }
        if (forLlm.isNotEmpty()) found += identifyWithLlm(forLlm)
        if (toRespell.isNotEmpty()) found += respell(toRespell)

        // 표지를 아직 확인하지 않은 곡은 표지 큐로 (식별과 따로, 늦게 붙어도 곡 이름은 먼저 보인다)
        val needCover = found.distinct().filter { repo.coverTarget(it)?.checkedAt == null }
        if (needCover.isNotEmpty()) sqs.send { it.queue(props.cover.queue).payload(json.writeValueAsString(CoverMessage(needCover))) }
        enrichTrigger.afterIdentify(msg.sessionId) // 식별이 다 끝났으면 지식 인입 시작 (L3-1)
        log.info("식별 묶음: 세션 {}, {}곡 중 찾음 {} (LLM {}곡, 표지 확인 {}), {}ms", msg.sessionId, msg.keys.size, found.size, forLlm.size, needCover.size, (System.nanoTime() - started) / 1_000_000)
    }

    /**
     * 규칙으로 못 푼 제목을 LLM으로 한 번에 정리하고 검증한 뒤 MusicBrainz로 식별한다.
     * ORIGINAL·LIVE·REMIX는 원곡으로 식별(취향에서는 같은 곡), COVER·MEDLEY·NOT_SONG·INSTRUMENTAL은 식별하지 않는다.
     */
    private fun identifyWithLlm(targets: List<Target>): List<Long> {
        val raws = targets.mapIndexed { i, t -> RawTitle(i, t.title, t.channel) }
        val result = cleaner.clean(raws)
        if (result.model != "fake") llmCalls.record("TITLE_CLEANUP", LlmUsage(result.model, raws.size, result.inputTokens, result.outputTokens, result.latencyMs))
        val byIndex = result.items.associateBy { it.index }.toMutableMap()
        // LLM 답은 실행마다 조금씩 달라서, 검증에서 떨어진 영상만 한 번 더 묻는다
        val invalid = raws.filter { r -> byIndex[r.index]?.takeIf { it.version in IDENTIFIABLE }?.let { CleanedValidator.accept(r, it) == null } == true }
        if (invalid.isNotEmpty()) {
            val again = cleaner.clean(invalid.mapIndexed { i, r -> r.copy(index = i) })
            if (again.model != "fake") llmCalls.record("TITLE_CLEANUP", LlmUsage(again.model, invalid.size, again.inputTokens, again.outputTokens, again.latencyMs))
            again.items.forEach { a -> invalid.getOrNull(a.index)?.let { r -> byIndex[r.index] = a.copy(index = r.index) } }
        }
        val medleyIds = medley(targets.indices.filter { byIndex[it]?.version == Version.MEDLEY }.map { raws[it] to byIndex[it] })
        return medleyIds + targets.mapIndexedNotNull { i, t ->
            val answer = byIndex[i]
            // 식별할 종류일 때만 검증한다 (곡이 아닌 영상은 곡명이 비어 있는 게 정상)
            val cleaned = answer?.takeIf { it.version in IDENTIFIABLE }?.let { CleanedValidator.accept(raws[i], it) }
            val (trackId, query, reason) = when {
                // 연주·커버는 식별하지 않지만 화면 표시용으로 정리된 곡명과 원곡 가수를 남긴다
                answer != null && (answer.version == Version.INSTRUMENTAL || answer.version == Version.COVER) ->
                    Triple(null, CleanedValidator.performanceNames(raws[i], answer)?.let { (song, original) -> Query(original.orEmpty(), song, false) }, answer.version.name)
                answer != null && answer.version !in IDENTIFIABLE -> Triple(null, null, answer.version.name)
                cleaned == null -> Triple(null, null, "INVALID")
                else -> {
                    // 표기 조합을 차례로 (가수·곡, 다른 가수 표기, 다른 곡 표기). 찾으면 멈춘다
                    val tries = listOf(cleaned.artist to cleaned.title, cleaned.artistAlt to cleaned.title, cleaned.artist to cleaned.titleAlt)
                        .filter { (a, b) -> !a.isNullOrBlank() && !b.isNullOrBlank() }.distinct()
                    var hit: Pair<Long, Query>? = null
                    for ((a, b) in tries) {
                        val info = try {
                            fetcher.identify(a!!, b!!)
                        } catch (e: HttpClientErrorException) {
                            failOne(t, e); return@mapIndexedNotNull null
                        } ?: continue
                        hit = tx.execute { repo.upsertTrack(info) }!! to Query(a, b, byChannelName = false)
                        break
                    }
                    Triple(hit?.first, hit?.second ?: tries.firstOrNull()?.let { Query(it.first!!, it.second!!, false) }, if (hit == null) "NOT_IN_MUSICBRAINZ" else null)
                }
            }
            tx.executeWithoutResult { repo.saveAlias(t.key, trackId, AliasMethod.LLM, query, titleAlt = cleaned?.titleAlt, reason = reason) }
            finish(t, trackId)
            trackId
        }
    }

    /**
     * 메들리 영상은 제목에 적힌 곡을 뽑아 곡마다 식별해 둔다 (메들리를 재생하면 든 곡을 모두 들은 것으로 본다).
     * 가수는 곡에 적힌 가수 → 영상에서 읽은 가수 순으로 찾고, 못 찾은 곡은 이름만 남겨 통계가 세션의 확인된 곡과 맞춘다.
     */
    private fun medley(videos: List<Pair<RawTitle, CleanedTitle?>>): List<Long> {
        if (videos.isEmpty()) return emptyList()
        val asked = videos.mapIndexed { i, (r, _) -> r.copy(index = i) }
        val answer = try {
            cleaner.medleySongs(asked)
        } catch (e: RuntimeException) {
            log.warn("메들리 곡을 받지 못했습니다: {}", e.message)
            return emptyList()
        }
        if (answer.model != "fake") llmCalls.record("MEDLEY_SONGS", LlmUsage(answer.model, asked.size, answer.inputTokens, answer.outputTokens, answer.latencyMs))
        val found = mutableListOf<Long>()
        answer.items.forEach { item ->
            val (raw, cleaned) = videos.getOrNull(item.index) ?: return@forEach
            val channel = raw.channel ?: return@forEach
            val videoArtist = cleaned?.let { listOfNotNull(it.artist, it.artistAlt) }.orEmpty()
            val songs = CleanedValidator.medley(raw, item.songs).map { (artist, song) ->
                val trackId = (listOfNotNull(artist) + videoArtist).distinct().firstNotNullOfOrNull { a ->
                    runCatching4xx { fetcher.identify(a, song) }?.let { info -> tx.execute { repo.upsertTrack(info) } }
                }
                trackId?.let(found::add)
                MedleySong(artist ?: videoArtist.firstOrNull(), song, trackId)
            }
            if (songs.isNotEmpty()) tx.executeWithoutResult { medleys.save(raw.title, channel, songs) }
        }
        return found
    }

    private inline fun runCatching4xx(block: () -> TrackInfo?): TrackInfo? = try { block() } catch (e: HttpClientErrorException) { null }

    /**
     * 규칙으로 못 찾은 Topic 곡을 LLM이 제안한 다른 표기로 다시 찾는다 (Seo InGuk → 서인국, TIME CAPSULE → 타임캡슐).
     * fetcher가 같은 가수 안에서 제목이 일치할 때만 찾았다고 답하므로, 지어낸 표기는 못 찾음으로 끝난다.
     * 이 경로로 찾은 곡은 reason RESPELLED로 남겨 나중에 검토할 수 있게 한다.
     */
    private fun sameLength(a: Long?, b: Long?) = a != null && b != null && kotlin.math.abs(a - b) <= SAME_LENGTH_MS

    data class Respell(val target: Target, val query: Query, val fallback: TrackInfo?)

    private fun askSpellings(items: List<Respell>): Map<Int, Spelling> {
        val asked = items.mapIndexed { i, r -> RawTitle(i, r.query.title, r.query.artist) }
        val answer = try {
            cleaner.spellings(asked)
        } catch (e: RuntimeException) {
            log.warn("표기 후보를 받지 못했습니다: {}", e.message)
            SpellResult(emptyList(), "fake", 0, 0, 0)
        }
        if (answer.model != "fake") llmCalls.record("TITLE_RESPELL", LlmUsage(answer.model, asked.size, answer.inputTokens, answer.outputTokens, answer.latencyMs))
        // LLM 답은 실행마다 달라질 수 있어 원인을 볼 수 있게 남긴다 (로컬 로그에만, 저장소·DB에는 남기지 않음)
        answer.items.forEach { s -> items.getOrNull(s.index)?.let { r -> log.info("표기 후보: {} - {} → 가수 {}, 곡 {}", r.query.artist, r.query.title, s.artists, s.titles) } }
        return answer.items.associateBy { it.index }
    }

    /** 후보 표기로 찾는다. 이미 찾은 녹음(fallback)이 있으면 발매일이 있고 길이가 5초 안으로 같은 결과만 받는다 (다른 곡 오연결 방지) */
    private fun tryCandidates(r: Respell, c: Spelling?): Pair<Long, Query>? {
        val (_, q, fallback) = r
        val artists = c?.artists.orEmpty()
        val titles = c?.titles.orEmpty()
        val tries = (artists.map { it to q.title } + titles.map { q.artist to it } + artists.flatMap { a -> titles.map { a to it } })
            .filter { it != (q.artist to q.title) }.distinct().take(MAX_RESPELL_TRIES)
        for ((a, b) in tries) {
            val info = runCatching4xx { fetcher.identify(a, b) } ?: continue
            if (fallback != null && (info.releaseDate == null || !sameLength(fallback.lengthMs, info.lengthMs))) continue
            return tx.execute { repo.upsertTrack(info) }!! to Query(a, b, byChannelName = false)
        }
        return null
    }

    private fun respell(items: List<Respell>): List<Long> {
        val first = askSpellings(items)
        val hits = items.indices.associateWith { tryCandidates(items[it], first[it]) }.toMutableMap()
        // 첫 답으로 못 찾은 곡만 한 번 더 묻는다 (묶음 구성이 바뀌면 답이 달라지는 경우가 있다)
        val missed = items.indices.filter { hits[it] == null }
        if (missed.isNotEmpty()) {
            val again = askSpellings(missed.map { items[it] })
            missed.forEachIndexed { j, i -> hits[i] = tryCandidates(items[i], again[j]) }
        }
        return items.mapIndexedNotNull { i, (t, q, fallback) ->
            val hit = hits[i]
            val kept = if (hit == null && fallback != null) tx.execute { repo.upsertTrack(fallback) }!! else null
            when {
                hit != null -> tx.executeWithoutResult { repo.saveAlias(t.key, hit.first, AliasMethod.LLM, hit.second, reason = "RESPELLED") }
                kept != null -> tx.executeWithoutResult { repo.saveAlias(t.key, kept, AliasMethod.RULE, q) }
                else -> tx.executeWithoutResult { repo.saveAlias(t.key, null, AliasMethod.RULE, q, reason = "NOT_IN_MUSICBRAINZ") }
            }
            val trackId = hit?.first ?: kept
            finish(t, trackId)
            trackId
        }
    }

    /** 요청 자체가 거절된 곡(4xx)은 다시 시도해도 같으므로 이 곡만 실패로 두고 묶음을 계속한다. 캐시에는 남기지 않아 다음 세션에서 다시 시도한다 */
    private fun failOne(t: Target, e: HttpClientErrorException) {
        log.warn("식별 거절: 세션 {}, 키 {}: {}", t.sessionId, t.key, e.statusCode)
        repo.finishTarget(t.sessionId, t.key, TargetStatus.FAILED)
    }

    private fun finish(t: Target, trackId: Long?) {
        tx.executeWithoutResult {
            if (repo.finishTarget(t.sessionId, t.key, TargetStatus.DONE) && trackId != null) {
                repo.linkListens(t, trackId)
                repo.linkSeeds(t, trackId)
            }
        }
    }
}
