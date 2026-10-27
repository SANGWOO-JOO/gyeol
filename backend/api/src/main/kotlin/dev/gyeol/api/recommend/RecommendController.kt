package dev.gyeol.api.recommend

import dev.gyeol.api.session.SessionService
import dev.gyeol.core.recommend.RecommendRepository
import org.springframework.jdbc.core.simple.JdbcClient
import dev.gyeol.api.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class Evidence(val id: Long, val source: String, val url: String)
data class BasedOn(val trackId: Long, val title: String, val artist: String)
data class RecView(
    val rank: Int, val trackId: Long, val title: String, val artist: String, val releaseTitle: String?, val coverUrl: String?,
    val score: Double, val reason: String?, val evidence: List<Evidence>, val basedOn: List<BasedOn>,
    /** L3-5 내 평가 (LIKE·DISLIKE, 없으면 null). 이유가 근거와 함께 쓰였는지(비교군)는 보내지 않는다 */
    val rating: String?, val reasonWrong: Boolean,
)
data class FeedbackRequest(val rating: String?, val reasonWrong: Boolean?)
/** status: NONE(시작 전) · CANDIDATES(후보 보강 중) · READY */
data class Recommendations(val status: String, val candidatesDone: Int, val candidatesTotal: Int, val summary: String?, val model: String?, val items: List<RecView>)

/** L3-4 추천 결과 (docs/l3-design.md 11장) */
@RestController
class RecommendController(private val sessions: SessionService, private val repo: RecommendRepository, private val jdbc: JdbcClient) {

    @GetMapping("/api/sessions/{token}/recommendations")
    fun get(@PathVariable token: String): Recommendations {
        val sid = sessions.find(token).id
        val rows = repo.recommendations(sid)
        val (done, total) = repo.candidateProgress(sid)
        if (rows.isEmpty()) return Recommendations(if (total == 0) "NONE" else "CANDIDATES", done, total, null, null, emptyList())
        val ids = (rows.map { it.trackId } + rows.flatMap { it.basedOn }).distinct()
        val tracks = names(ids)
        val chunks = rows.flatMap { it.evidence }.distinct().takeIf { it.isNotEmpty() }?.let { ev ->
            jdbc.sql("SELECT id, source, url FROM knowledge_chunk WHERE id = ANY (:ids)").param("ids", ev.toTypedArray())
                .query { rs, _ -> Evidence(rs.getLong(1), rs.getString(2), rs.getString(3)) }.list().associateBy { it.id }
        }.orEmpty()
        val feedback = repo.feedback(sid)
        val items = rows.mapNotNull { r ->
            val t = tracks[r.trackId] ?: return@mapNotNull null
            RecView(r.rank, r.trackId, t.title, t.artist, t.releaseTitle, t.coverUrl, r.score, r.reason,
                r.evidence.mapNotNull { chunks[it] }, r.basedOn.mapNotNull { b -> tracks[b]?.let { BasedOn(b, it.title, it.artist) } },
                feedback[r.trackId]?.rating, feedback[r.trackId]?.reasonWrong ?: false)
        }
        return Recommendations("READY", done, total, repo.summary(sid), rows.first().model, items)
    }

    /** L3-5 추천 곡 평가. 다시 보내면 바뀐다 */
    @PutMapping("/api/sessions/{token}/recommendations/{trackId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun feedback(@PathVariable token: String, @PathVariable trackId: Long, @RequestBody body: FeedbackRequest) {
        val rating = body.rating?.takeIf { it == "LIKE" || it == "DISLIKE" } ?: throw ApiException.validation("rating은 LIKE 또는 DISLIKE입니다")
        val sid = sessions.find(token).id
        if (!repo.saveFeedback(sid, trackId, rating, body.reasonWrong ?: false)) throw ApiException(HttpStatus.NOT_FOUND, "NOT_RECOMMENDED", "추천된 곡이 아닙니다")
    }

    @DeleteMapping("/api/sessions/{token}/recommendations/{trackId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(@PathVariable token: String, @PathVariable trackId: Long) = repo.deleteFeedback(sessions.find(token).id, trackId)

    private data class Names(val title: String, val artist: String, val releaseTitle: String?, val coverUrl: String?)

    /** 표시 이름은 사용자가 본 표기가 있으면 그것 (StatsService와 같은 규칙) */
    private fun names(ids: List<Long>): Map<Long, Names> =
        jdbc.sql(
            """
            SELECT t.id,
                   coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_title) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED'), t.title),
                   coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_artist) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED'), a.name),
                   t.release_title, t.cover_url
            FROM track t JOIN artist a ON a.id = t.artist_id WHERE t.id = ANY (:ids)
            """.trimIndent(),
        ).param("ids", ids.toTypedArray()).query { rs, _ -> rs.getLong(1) to Names(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }.list().toMap()
}
