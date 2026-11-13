package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.map.MapRepository
import dev.gyeol.core.room.CodeMatchParts
import dev.gyeol.core.room.CodeMatchRepository
import dev.gyeol.core.room.CodeMatchRow
import dev.gyeol.core.room.CodeMatchScore
import dev.gyeol.core.room.Member
import dev.gyeol.core.room.RoomRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

data class CodeMatchRequest(val a: Long?, val b: Long?)

data class CodeMatchPerson(val memberId: Long, val nickname: String, val colorIdx: Int, val avatarCharacterId: Long?, val songCount: Int)
data class CodeMatchPartsView(val genre: Double, val distance: Double, val artist: Double, val song: Double)

/**
 * status: REASONING(이유 쓰는 중) · DONE · NO_REASON(근거 있는 이유를 못 씀, 점수·항목만).
 * confidence: 둘 중 적은 쪽 곡 수로 LOW(5~9)·MEDIUM(10~19)·HIGH(20~). 웹은 LOW·MEDIUM이면 "각자 10곡(20곡)을 넘기면 더 정확해져요".
 * stale: 저장 뒤 두 사람의 곡이나 지도 판이 바뀜 (다시 계산하면 새 점수)
 */
data class CodeMatchView(
    val id: Long, val a: CodeMatchPerson, val b: CodeMatchPerson, val score: Int, val parts: CodeMatchPartsView,
    val sharedArtists: List<String>, val sharedGalaxies: List<String>, val sharedSongs: Int,
    val reason: String?, val evidence: List<EvidenceView>, val status: String,
    val confidence: String, val minSongs: Int, val stale: Boolean, val createdAt: Instant,
)

/**
 * ⑤ 놀이터 · 코드 맞추기 (room-plan.md ⑰, room-api-db.md 3절·11절).
 * 점수는 여기서 바로(LLM 없음), 이유는 worker(code-match 큐)가 근거 번호와 함께 쓴다. 결과는 같은 방 참가자 모두가 본다.
 */
@Service
class CodeMatchService(
    private val rooms: RoomRepository,
    private val matches: CodeMatchRepository,
    private val maps: MapRepository,
    private val sender: AfterCommitSender,
    private val json: JsonMapper,
    private val props: GyeolProperties,
) {
    private val p get() = props.room.codeMatch

    /** 저장 결과가 있으면 200, 새로 계산하면 202 (점수는 바로, 이유는 뒤에서) */
    @Transactional
    fun match(ctx: RoomContext, body: CodeMatchRequest): Pair<HttpStatus, CodeMatchView> {
        val (ma, mb) = pair(ctx, body.a, body.b)
        // 하루 횟수를 정확히 세려고 방 행을 잠근다 (담기·참가와 같은 잠금)
        rooms.lock(ctx.room.id)
        val pa = matches.songPoints(ma.id)
        val pb = matches.songPoints(mb.id)
        if (minOf(pa.size, pb.size) < p.minSongs) {
            throw ApiException.conflict("NOT_ENOUGH_SONGS", "두 사람 모두 노래를 ${p.minSongs}곡 이상 담아야 코드를 맞춰 볼 수 있어요")
        }
        val hash = CodeMatchScore.inputHash(pa, pb, maps.activeVersion())
        matches.find(ctx.room.id, ma.id, mb.id, hash)?.let { return HttpStatus.OK to view(it, ma, mb, pa.size, pb.size, hash) }
        if (matches.countToday(ctx.room.id, todayKst()) >= p.dailyLimit) {
            throw ApiException.tooMany("CODE_MATCH_LIMIT", "오늘은 이 우주에서 코드 맞추기를 ${p.dailyLimit}번 했어요. 내일 또 해 주세요")
        }
        val parts = CodeMatchScore.parts(pa, pb)
        val id = matches.insert(ctx.room.id, ma.id, mb.id, hash, CodeMatchScore.score(parts, p.weights()), json.writeValueAsString(parts))
            ?: return HttpStatus.OK to view(matches.find(ctx.room.id, ma.id, mb.id, hash)!!, ma, mb, pa.size, pb.size, hash)
        sender.send(props.room.codeMatchQueue, json.writeValueAsString(mapOf("matchId" to id)))
        return HttpStatus.ACCEPTED to view(matches.get(id)!!, ma, mb, pa.size, pb.size, hash)
    }

    /** 이 두 사람의 최근 결과 (이유를 기다리는 화면이 몇 초마다 읽는다) */
    fun latest(ctx: RoomContext, a: Long?, b: Long?): CodeMatchView {
        val (ma, mb) = pair(ctx, a, b)
        val row = matches.latest(ctx.room.id, ma.id, mb.id) ?: throw ApiException(HttpStatus.NOT_FOUND, "CODE_MATCH_NOT_FOUND", "아직 맞춰 본 적이 없어요")
        val pa = matches.songPoints(ma.id)
        val pb = matches.songPoints(mb.id)
        return view(row, ma, mb, pa.size, pb.size, CodeMatchScore.inputHash(pa, pb, maps.activeVersion()))
    }

    /** 같은 방의 나가지 않은 두 사람, 항상 id 작은 쪽이 a */
    private fun pair(ctx: RoomContext, a: Long?, b: Long?): Pair<Member, Member> {
        if (a == null || b == null) throw ApiException.validation("두 사람을 골라 주세요")
        if (a == b) throw ApiException.validation("서로 다른 두 사람을 골라 주세요")
        val members = rooms.members(ctx.room.id).filter { it.removedAt == null }.associateBy { it.id }
        val ma = members[minOf(a, b)]
        val mb = members[maxOf(a, b)]
        if (ma == null || mb == null) throw ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "이 우주에 없는 사람이에요")
        return ma to mb
    }

    private fun view(row: CodeMatchRow, ma: Member, mb: Member, countA: Int, countB: Int, currentHash: String): CodeMatchView {
        val parts = json.readValue(row.partsJson, CodeMatchParts::class.java)
        return CodeMatchView(
            row.id,
            CodeMatchPerson(ma.id, ma.nickname, ma.colorIdx, ma.avatarCharacterId, countA),
            CodeMatchPerson(mb.id, mb.nickname, mb.colorIdx, mb.avatarCharacterId, countB),
            row.score, CodeMatchPartsView(round2(parts.genre), round2(parts.distance), round2(parts.artist), round2(parts.song)),
            parts.sharedArtists.take(10), parts.sharedGalaxies.take(5), parts.sharedSongs,
            row.reason, maps.chunksByIds(row.evidence).map { EvidenceView(it.id, it.source, it.url) }, row.status,
            CodeMatchScore.confidence(minOf(countA, countB)), p.minSongs, row.inputHash != currentHash, row.createdAt,
        )
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}

@RestController
class CodeMatchController(private val service: CodeMatchService) {
    @PostMapping("/api/rooms/{rid}/code-match")
    fun match(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestBody body: CodeMatchRequest): ResponseEntity<CodeMatchView> {
        val (status, view) = service.match(ctx, body)
        return ResponseEntity.status(status).body(view)
    }

    @GetMapping("/api/rooms/{rid}/code-match")
    fun latest(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestParam a: Long?, @RequestParam b: Long?): CodeMatchView =
        service.latest(ctx, a, b)
}
