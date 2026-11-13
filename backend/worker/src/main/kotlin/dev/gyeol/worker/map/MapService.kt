package dev.gyeol.worker.map

import dev.gyeol.core.knowledge.Chunk
import dev.gyeol.core.knowledge.EntityType
import dev.gyeol.core.knowledge.KnowledgeRepository
import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.core.map.ArtistRef
import dev.gyeol.core.map.MapRepository
import dev.gyeol.worker.describe.EmbedClient
import dev.gyeol.worker.identify.FetcherClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.HttpClientErrorException
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.sqrt

/** 초기 지도 나라: ISO 코드와 Last.fm 나라 이름 */
data class SeedCountry(val iso: String, val lastfm: String)

/**
 * 음악 지도 데이터 (room-plan.md 8절, room-api-db.md V23).
 * 1. seed: Last.fm 나라별 인기 가수 → MusicBrainz 출신 국가로 거르기 → 가수·근거 조각
 * 2. describe: 가수 설명 (RAG, 근거 번호)
 * 3. embed: 원문(나라 단어 제거) 벡터 0.5 + 설명 벡터 0.5
 * 4. (tools/map-build) UMAP 2D·구역 묶기 → map_version
 * 5. nameGalaxies: 구역 이름
 * 6. prepare: 지도에 없는 가수(방에서 처음 담김) → 근거·설명·벡터 → 가까운 가수 5명 좌표 평균, 구역은 이웃 다수결
 */
@Service
class MapService(
    private val map: MapRepository,
    private val knowledge: KnowledgeRepository,
    private val fetcher: FetcherClient,
    private val embed: EmbedClient,
    private val llm: MapLlm,
    private val llmCalls: LlmCallRepository,
    private val tx: TransactionTemplate,
    private val props: dev.gyeol.worker.WorkerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 근거가 90일 안에 가져온 것이면 다시 가져오지 않는다 (곡 보강과 같음) */
        val REFRESH: Duration = Duration.ofDays(90)
        const val NEIGHBORS = 5
        const val RAW_WEIGHT = 0.5f

        /** 근거 원문에서 나라·도시 단어를 지운다 (11-11 측정: 나라로 뭉치는 것을 줄임) */
        private val NATION = Regex(
            "(south[ -]?korean?|north[ -]?korean?|korean?|republic of korea|japan(ese)?|american?|united states( of america)?|\\busa?\\b|seoul|tokyo|osaka|" +
                "los angeles|new york|nashville|atlanta|california|대한민국|한국(인|의)?|일본(인|의)?|미국(인|의)?|서울|도쿄|韓国|日本|アメリカ|東京|大阪)",
            RegexOption.IGNORE_CASE,
        )

        fun rawText(name: String, chunks: List<String>): String =
            (name + ". " + chunks.joinToString(" | ") { NATION.replace(it.take(600), " ") }).take(1500)

        /** 두 벡터를 각각 정규화해 섞고 다시 정규화 */
        fun mix(raw: FloatArray, descr: FloatArray?, rawWeight: Float = RAW_WEIGHT): FloatArray {
            val r = normalize(raw)
            if (descr == null) return r
            val d = normalize(descr)
            return normalize(FloatArray(r.size) { rawWeight * r[it] + (1 - rawWeight) * d[it] })
        }

        fun normalize(v: FloatArray): FloatArray {
            val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: return v
            return FloatArray(v.size) { v[it] / n }
        }
    }

    // ── 1. 초기 가수 ──

    /** 나라마다 [perCountry]명이 될 때까지 모은다. 이미 모은 가수는 건너뛰어, 끊겨도 이어서 돌릴 수 있다 */
    fun seed(countries: List<SeedCountry>, perCountry: Int, progress: (String) -> Unit = {}) {
        for (c in countries) {
            val seen = map.seededMbids(c.iso).toMutableSet()
            var count = map.seedCount(c.iso)
            var page = 1
            while (count < perCountry && page <= 30) {
                val items = fetcher.geoTop(c.lastfm, page++).path("items").toList()
                if (items.isEmpty()) break
                for (item in items) {
                    if (count >= perCountry) break
                    val name = item.path("name").asString()
                    val mbid = item.path("mbid").asString("").ifEmpty { null }
                    if (mbid != null && runCatching { UUID.fromString(mbid) }.getOrNull() in seen) continue
                    val a = runCatching { fetcher.mbArtist(mbid, name) }.getOrElse { log.warn("가수 조회 실패: {} {}", name, it.message); null } ?: continue
                    if (!a.path("found").asBoolean(false) || a.path("country").asString("") != c.iso) continue
                    val artistMbid = UUID.fromString(a.path("mbid").asString())
                    if (artistMbid in seen) continue
                    // 근거를 먼저 받는다. 외부가 잠깐 안 되면 30초 쉬고 다시, 3번 다 실패하면 이 가수만 건너뛴다 (한 명 때문에 전체가 멈추지 않게)
                    if (!retryKnowledge(artistMbid, name, wait = props.map.knowledgeRetryWait)) { log.warn("초기 가수 건너뜀(근거 실패): {} {}", c.iso, name); continue }
                    seen += artistMbid
                    val artistId = tx.execute {
                        val id = map.upsertArtist(artistMbid, a.path("name").asString(), a.path("sortName").asString("").ifEmpty { null },
                            a.path("aliases").toList().map { it.asString() } + listOf(name).filter { it != a.path("name").asString() })
                        map.markSeed(id, c.iso, count + 1)
                        id
                    }!!
                    count++
                    if (count % 10 == 0) progress("${c.iso} $count/$perCountry")
                    log.info("초기 가수: {} {} {} (#{})", c.iso, count, name, artistId)
                }
            }
            progress("${c.iso} 끝 $count/$perCountry")
        }
    }

    /** 근거 받기를 [tries]번까지. 일시 오류(5xx·연결)만 다시 시도하고, 성공하면 true */
    fun retryKnowledge(mbid: UUID, name: String, tries: Int = 3, wait: Duration = Duration.ofSeconds(30)): Boolean {
        repeat(tries) { i ->
            try {
                fetchKnowledge(mbid, name); return true
            } catch (e: HttpClientErrorException) {
                return false
            } catch (e: RuntimeException) {
                log.warn("근거 받기 실패 {}/{}: {} {}", i + 1, tries, name, e.message)
                if (i < tries - 1) Thread.sleep(wait.toMillis())
            }
        }
        return false
    }

    private fun fetchKnowledge(mbid: UUID, name: String) {
        if (knowledge.isFresh(EntityType.ARTIST, mbid, Instant.now().minus(REFRESH))) return
        val r = fetcher.knowledge("artist", mbid.toString(), name)
        tx.executeWithoutResult { knowledge.saveEntity(EntityType.ARTIST, mbid, chunks(r)) }
    }

    private fun chunks(r: JsonNode) = r.path("chunks").toList().map {
        Chunk(it.path("source").asString(), it.path("lang").asString(), it.path("url").asString(), it.path("license").asString(), it.path("text").asString())
    }

    // ── 2. 설명 ──

    /** 설명이 없는 가수를 [limit]명까지. 만든 수 */
    fun describeAll(limit: Int, progress: (String) -> Unit = {}): Int {
        val todo = map.needDescription(limit)
        todo.forEachIndexed { i, a ->
            describe(a)
            if ((i + 1) % 10 == 0) progress("설명 ${i + 1}/${todo.size}")
        }
        return todo.size
    }

    fun describe(a: ArtistRef) {
        val chunks = map.chunks(a.mbid)
        if (chunks.isEmpty()) {
            map.saveDescription(a.artistId, null, emptyList(), "none"); return
        }
        val d = llm.describeArtist(a.name, chunks)
        if (d.usage.model != "fake") llmCalls.record("ARTIST_DESCRIBE", LlmUsage(d.usage.model, 1, d.usage.inputTokens, d.usage.outputTokens, d.usage.latencyMs))
        map.saveDescription(a.artistId, d.text, d.evidence, d.usage.model)
    }

    /**
     * 이미 저장된 설명 중 [DescriptionGuard]에 걸리는 것을 지운다. 지운 가수는 [embedAll]에서 원문 벡터만으로 다시 만든다.
     * Bedrock을 다시 부르지 않는다. 지운 수
     */
    fun cleanDescriptions(): Int {
        val bad = map.describedTexts().filter { (_, text) -> DescriptionGuard.usable(text) == null }
        bad.forEach { (artistId, _) -> map.clearDescription(artistId) }
        return bad.size
    }

    // ── 3. 벡터 ──

    fun embedAll(progress: (String) -> Unit = {}): Int {
        var total = 0
        while (true) {
            val batch = map.needVector(64)
            if (batch.isEmpty()) return total
            embedArtists(batch)
            total += batch.size
            progress("벡터 $total")
        }
    }

    fun embedArtists(artists: List<ArtistRef>) {
        val raws = artists.map { rawText(it.name, map.chunks(it.mbid).map { c -> c.text }) }
        val descs = artists.map { map.description(it.artistId) }
        val (model, rawVecs) = embed.embed(raws, "passage")
        val withDesc = descs.withIndex().filter { it.value != null }
        val descVecs = if (withDesc.isEmpty()) emptyMap() else embed.embed(withDesc.map { it.value!! }, "passage").second
            .let { v -> withDesc.mapIndexed { j, iv -> iv.index to v[j] }.toMap() }
        artists.forEachIndexed { i, a ->
            val d = descVecs[i]
            map.saveVector(a.artistId, model, rawVecs[i], d, mix(rawVecs[i], d), sha256(raws[i] + "\n" + (descs[i] ?: "")))
        }
    }

    // ── 5. 구역 이름 ──

    fun nameGalaxies(progress: (String) -> Unit = {}): Int {
        val version = map.activeVersion() ?: error("활성 지도 판이 없습니다 (tools/map-build 먼저)")
        val todo = map.unnamedGalaxies(version)
        todo.forEachIndexed { i, g ->
            val members = map.galaxyMembers(g.id, 12).filter { it.description != null }.map { GalaxyMemberText(it.description!!, it.evidence) }
            if (members.isEmpty()) return@forEachIndexed
            val n = llm.nameGalaxy(members)
            if (n.usage.model != "fake") llmCalls.record("GALAXY_NAME", LlmUsage(n.usage.model, members.size, n.usage.inputTokens, n.usage.outputTokens, n.usage.latencyMs))
            n.name?.let { map.nameGalaxy(g.id, it, n.reason, n.evidence) }
            progress("구역 ${i + 1}/${todo.size}")
        }
        return todo.size
    }

    // ── 6. 새 가수 ──

    /**
     * 지도에 없는 가수 하나를 올린다. 근거 → 설명 → 벡터 → 이웃 평균 자리. 끝나면 이 가수가 담긴 방들의 지도를 새로 그리게 한다.
     * 활성 판이 없으면(초기 지도 전) 벡터까지만 만들고 끝낸다.
     */
    fun prepare(artistId: Long) {
        val a = map.artist(artistId) ?: return
        val version = map.activeVersion()
        if (version != null && map.positions(version, listOf(artistId)).isNotEmpty()) {
            map.finishPrepare(artistId, "DONE"); return
        }
        try {
            map.ensureProfile(artistId, null)
            map.prepareStep(artistId, "KNOWLEDGE")
            fetchKnowledge(a.mbid, a.name)
            map.prepareStep(artistId, "DESCRIBE")
            if (map.description(artistId) == null) describe(a)
            map.prepareStep(artistId, "VECTOR")
            embedArtists(listOf(a))
            if (version != null) {
                map.prepareStep(artistId, "PLACE")
                place(version, artistId)
            }
            tx.executeWithoutResult {
                map.finishPrepare(artistId, "DONE")
                map.bumpRoomsWithArtist(artistId)
            }
        } catch (e: HttpClientErrorException) {
            // 외부가 거절(4xx)한 가수는 다시 해도 같다. 지도에는 임시 자리로 남는다
            map.finishPrepare(artistId, "FAILED", e.message)
        }
    }

    /** 가까운 가수 [NEIGHBORS]명의 좌표 평균, 구역은 이웃 다수결. 설명 없는 가수는 원문 벡터끼리 비교한다 */
    fun place(version: Long, artistId: Long) {
        val (v, byRaw) = map.placeVector(artistId) ?: return
        val near = map.nearestPlaced(version, v, NEIGHBORS, listOf(artistId), byRaw)
        if (near.isEmpty()) return
        val x = near.map { it.x }.average()
        val y = near.map { it.y }.average()
        val galaxy = near.mapNotNull { it.galaxyId }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        map.savePosition(version, artistId, x, y, galaxy, "NEIGHBOR")
        log.info("새 가수 자리: #{} ← 이웃 {} ({}), 구역 {}", artistId, near.map { it.name }, "%.3f, %.3f".format(x, y), galaxy)
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
