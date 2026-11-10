package dev.gyeol.worker.guess

import dev.gyeol.core.identify.GuessTarget
import dev.gyeol.core.identify.MedleyRepository
import dev.gyeol.core.identify.MedleySong
import dev.gyeol.core.identify.SearchText
import dev.gyeol.core.identify.TitleGuess
import dev.gyeol.core.identify.TitleGuessRepository
import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.llm.CleanedTitle
import dev.gyeol.worker.llm.CleanedValidator
import dev.gyeol.worker.llm.RawTitle
import dev.gyeol.worker.llm.TitleCleaner
import dev.gyeol.worker.llm.Version
import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

data class GuessMessage(val items: List<GuessTarget>)

/** 식별이 끝난 세션에서 많이 들은 방송·일반 채널 영상을 골라 묶음으로 큐에 넣는다. 여러 번 불려도 이미 뽑은 영상은 건너뛴다 */
@Component
class GuessPlanner(
    private val repo: TitleGuessRepository,
    private val sqs: SqsTemplate,
    private val json: JsonMapper,
    private val props: WorkerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun plan(sessionId: Long) {
        val g = props.guess
        val targets = repo.targets(sessionId, g.minPlays, g.maxVideos, g.retry.toDays().toInt())
        targets.chunked(g.batch).forEach { items -> sqs.send { it.queue(g.queue).payload(json.writeValueAsString(GuessMessage(items))) } }
        log.info("가수·곡 뽑기 계획: 세션 {}, 영상 {}개", sessionId, targets.size)
    }
}

/** SQS guess 큐 (WORKER_ROLE에 guess가 있을 때만). 실패해도 해당 재생이 가수 순위에서 빠질 뿐이므로 실패 큐는 기록용 */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('guess')")
class GuessListener(private val service: GuessService, private val json: JsonMapper) {
    @SqsListener("\${gyeol.worker.guess.queue}", maxConcurrentMessages = "2", maxMessagesPerPoll = "2")
    fun onMessage(body: String) = service.process(json.readValue(body, GuessMessage::class.java))
}

@Service
class GuessService(
    private val medleys: MedleyRepository,
    private val repo: TitleGuessRepository,
    private val cleaner: TitleCleaner,
    private val llmCalls: LlmCallRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val singable = setOf(Version.ORIGINAL, Version.LIVE, Version.REMIX, Version.COVER)

    fun process(msg: GuessMessage) {
        val raws = msg.items.mapIndexed { i, t -> RawTitle(i, t.title, t.channel) }
        val result = cleaner.clean(raws)
        if (result.model != "fake") llmCalls.record("TITLE_GUESS", LlmUsage(result.model, raws.size, result.inputTokens, result.outputTokens, result.latencyMs))
        val byIndex = result.items.associateBy { it.index }
        var kept = 0
        // 메들리는 든 곡 이름을 남겨, 통계가 세션의 확인된 곡과 맞춘다 (MusicBrainz는 부르지 않는다)
        val medleyIdx = msg.items.indices.filter { byIndex[it]?.version == Version.MEDLEY }
        if (medleyIdx.isNotEmpty()) {
            runCatching { cleaner.medleySongs(medleyIdx.mapIndexed { j, i -> raws[i].copy(index = j) }) }.onSuccess { m ->
                if (m.model != "fake") llmCalls.record("MEDLEY_SONGS", LlmUsage(m.model, medleyIdx.size, m.inputTokens, m.outputTokens, m.latencyMs))
                m.items.forEach { item ->
                    val i = medleyIdx.getOrNull(item.index) ?: return@forEach
                    val songs = CleanedValidator.medley(raws[i], item.songs).map { (a, s) -> MedleySong(a ?: byIndex[i]?.artist, s) }
                    if (songs.isNotEmpty()) medleys.save(msg.items[i].title, msg.items[i].channel, songs)
                }
            }.onFailure { log.warn("메들리 곡을 받지 못했습니다: {}", it.message) }
        }
        msg.items.forEachIndexed { i, t ->
            val answer = byIndex[i] ?: return@forEachIndexed // 답이 빠진 영상은 저장하지 않아 다음에 다시 묻는다
            val accepted = answer.takeIf { it.version in singable }?.let { CleanedValidator.accept(raws[i], it) }?.takeIf { ArtistPlacement.ok(t.title, t.channel, it) }
            if (answer.version == Version.INSTRUMENTAL) {
                val names = CleanedValidator.performanceNames(raws[i], answer)
                repo.save(TitleGuess(t.title, t.channel, names?.second, null, names?.first, null, answer.version.name))
                return@forEachIndexed
            }
            if (accepted != null) kept++
            repo.save(TitleGuess(t.title, t.channel, accepted?.artist, accepted?.artistAlt, accepted?.title, accepted?.titleAlt, answer.version.name))
        }
        log.info("가수·곡 뽑기: 영상 {}개 중 {}개 사용, 모델 {}, 토큰 {}/{}", raws.size, kept, result.model, result.inputTokens, result.outputTokens)
    }
}

/**
 * 뽑은 가수 이름이 프로그램 이름에서 온 것이 아닌지 본다 (예: "[진행자의 러브레터]"의 진행자).
 * 괄호 밖이나 채널 이름에 있어야 하고, 제목 맨 앞 괄호 뒤에 구분자가 오는 "[가수]-곡" 형태는 괄호 안도 인정한다.
 */
object ArtistPlacement {
    private val BRACKET = Regex("""\[[^\]]*]|【[^】]*】""")
    private val HEAD = Regex("""^\s*[\[【]([^\]】]*)[\]】]\s*[-–:_]""")

    fun ok(title: String, channel: String, c: CleanedTitle): Boolean {
        val outside = SearchText.norm(title.replace(BRACKET, " ")) + SearchText.norm(channel)
        val head = HEAD.find(title)?.let { SearchText.norm(it.groupValues[1]) }.orEmpty()
        return listOfNotNull(c.artist, c.artistAlt).map(SearchText::norm).any { it.isNotEmpty() && (it in outside || it in head) }
    }
}
