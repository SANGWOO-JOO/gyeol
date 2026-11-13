package dev.gyeol.core.room

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** 50곡 선물 (room-plan.md ⑯). 상자 상태 흐름: WAITING → READY → GENERATING → OPENED (기본 캐릭터도 OPENED, 저장 자체가 실패하면 FAILED) */
enum class GiftStatus { WAITING, READY, GENERATING, OPENED, FAILED }

data class GiftBox(
    val id: Long, val memberId: Long, val milestone: Int, val status: GiftStatus, val earnedAt: Instant,
    val readyOn: LocalDate?, val openedAt: Instant?, val characterId: Long?,
)

data class GiftCharacter(
    val id: Long, val memberId: Long, val giftBoxId: Long?, val s3Key: String, val isFallback: Boolean, val createdAt: Instant,
    /** 만든 모델. 기본 캐릭터는 preset:DAILY_LIMIT(하루 한도) · preset:FAILED(생성 실패) · preset(11-14 전) */
    val model: String = "",
)

/** 아래 바·탭 배지·담은 직후 알림에 쓰는 요약. remaining = 다음 단계 − 지금 곡 수 (노래를 빼면 50보다 클 수 있음) */
data class GiftSummary(
    val songCount: Int, val bestCount: Int, val nextMilestone: Int, val remaining: Int,
    val receivedToday: Boolean, val waiting: Int, val readyBoxId: Long?,
    /** 서비스 전체 오늘 새 그림 한도와 남은 장수 (선착순, 0이면 상자를 내일 열거나 기본 캐릭터로 지금 받기) */
    val imageDailyLimit: Int = 0, val imagesLeftToday: Int = 0,
)

@Repository
class GiftRepository(private val jdbc: JdbcClient) {

    companion object {
        const val STEP = 50
        /** 기본 캐릭터 그림 수 (core resources gift/preset-N.svg) */
        const val PRESETS = 6
        fun presetKey(n: Int) = "preset:${(n % PRESETS + PRESETS) % PRESETS + 1}"
    }

    private val boxMapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
        GiftBox(
            rs.getLong("id"), rs.getLong("member_id"), rs.getInt("milestone"), GiftStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("earned_at").toInstant(), rs.getDate("ready_on")?.toLocalDate(), rs.getTimestamp("opened_at")?.toInstant(),
            rs.getObject("character_id") as Long?,
        )
    }

    private val characterMapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
        GiftCharacter(
            rs.getLong("id"), rs.getLong("member_id"), rs.getObject("gift_box_id") as Long?, rs.getString("s3_key"),
            rs.getBoolean("is_fallback"), rs.getTimestamp("created_at").toInstant(), rs.getString("model"),
        )
    }

    private fun songCount(memberId: Long): Int =
        jdbc.sql("SELECT count(*) FROM room_song WHERE member_id = :m").param("m", memberId).query(Int::class.java).single()

    private fun progress(memberId: Long): Pair<Int, Int> {
        jdbc.sql("INSERT INTO member_progress (member_id) VALUES (:m) ON CONFLICT DO NOTHING").param("m", memberId).update()
        return jdbc.sql("SELECT best_count, next_milestone FROM member_progress WHERE member_id = :m").param("m", memberId)
            .query { rs, _ -> rs.getInt(1) to rs.getInt(2) }.single()
    }

    private fun readyToday(memberId: Long, today: LocalDate): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM gift_box WHERE member_id = :m AND ready_on = :d)")
            .param("m", memberId).param("d", today).query(Boolean::class.java).single()

    /**
     * 노래를 담은 직후, 담기와 같은 트랜잭션에서 부른다 (방 행을 잠근 상태라 같은 사람의 담기가 겹치지 않는다).
     * 곡 수가 최고 기록을 넘으면 기록을 올리고, 다음 50곡 단위에 닿았으면 상자를 만든다. 오늘 이미 받은 상자가 있으면 WAITING.
     * 새로 만든 상자 (없으면 null)
     */
    fun onSongAdded(memberId: Long, today: LocalDate): GiftBox? {
        val count = songCount(memberId)
        var (best, next) = progress(memberId)
        if (count <= best) return null
        best = count
        var created: GiftBox? = null
        while (best >= next) {
            val ready = !readyToday(memberId, today)
            val id = jdbc.sql(
                """
                INSERT INTO gift_box (member_id, milestone, status, ready_on) VALUES (:m, :ms, :s, :d)
                ON CONFLICT (member_id, milestone) DO NOTHING RETURNING id
                """.trimIndent(),
            ).param("m", memberId).param("ms", next).param("s", if (ready) "READY" else "WAITING").param("d", if (ready) today else null)
                .query(Long::class.java).optional().orElse(null)
            if (id != null) created = box(id)
            next += STEP
        }
        jdbc.sql("UPDATE member_progress SET best_count = :b, next_milestone = :n WHERE member_id = :m")
            .param("b", best).param("n", next).param("m", memberId).update()
        return created
    }

    /**
     * 기다리는 상자를 하루 1개 READY로 올린다 (다음 날 첫 조회 때). 아직 안 연 READY 상자가 있거나 오늘 이미 READY가 된 상자가 있으면 그대로.
     * 동시 조회는 (member_id, ready_on) 고유 색인이 막는다
     */
    fun promoteWaiting(memberId: Long, today: LocalDate): Boolean = runCatching {
        jdbc.sql(
            """
            UPDATE gift_box SET status = 'READY', ready_on = :d
            WHERE id = (SELECT id FROM gift_box WHERE member_id = :m AND status = 'WAITING' ORDER BY milestone LIMIT 1)
              AND NOT EXISTS (SELECT 1 FROM gift_box WHERE member_id = :m AND (status = 'READY' OR ready_on = :d))
            """.trimIndent(),
        ).param("m", memberId).param("d", today).update() == 1
    }.getOrElse { e -> if (e is org.springframework.dao.DuplicateKeyException) false else throw e }

    fun summary(memberId: Long, today: LocalDate, imageDailyLimit: Int = 0): GiftSummary {
        val count = songCount(memberId)
        val (best, next) = progress(memberId)
        val boxes = boxes(memberId)
        return GiftSummary(
            count, best, next, next - count,
            boxes.any { it.readyOn == today },
            boxes.count { it.status == GiftStatus.WAITING },
            boxes.firstOrNull { it.status == GiftStatus.READY }?.id,
            imageDailyLimit, imagesLeft(today, imageDailyLimit),
        )
    }

    /** 오늘 남은 새 그림 수 = max(0, 한도 − 오늘 만든 그림) */
    fun imagesLeft(today: LocalDate, limit: Int): Int = (limit - imagesToday(today)).coerceAtLeast(0)

    fun boxes(memberId: Long): List<GiftBox> =
        jdbc.sql("SELECT * FROM gift_box WHERE member_id = :m ORDER BY milestone").param("m", memberId).query(boxMapper).list()

    fun box(id: Long): GiftBox? = jdbc.sql("SELECT * FROM gift_box WHERE id = :id").param("id", id).query(boxMapper).optional().orElse(null)

    /** READY → GENERATING. 내 상자이고 READY일 때만 true */
    fun startOpening(boxId: Long, memberId: Long): Boolean =
        jdbc.sql("UPDATE gift_box SET status = 'GENERATING' WHERE id = :id AND member_id = :m AND status = 'READY'")
            .param("id", boxId).param("m", memberId).update() == 1

    /** 캐릭터를 저장하고 상자를 연다. GENERATING이 아니면(메시지를 두 번 받은 경우) 아무것도 하지 않고 null */
    fun finishOpening(boxId: Long, s3Key: String, prompt: String?, model: String, fallback: Boolean): Long? {
        val b = box(boxId)?.takeIf { it.status == GiftStatus.GENERATING } ?: return null
        val characterId = jdbc.sql(
            """
            INSERT INTO gift_character (member_id, gift_box_id, s3_key, prompt, model, is_fallback)
            VALUES (:m, :b, :k, :p, :model, :f) RETURNING id
            """.trimIndent(),
        ).param("m", b.memberId).param("b", boxId).param("k", s3Key).param("p", prompt).param("model", model).param("f", fallback)
            .query(Long::class.java).single()
        jdbc.sql("UPDATE gift_box SET status = 'OPENED', opened_at = now(), character_id = :c WHERE id = :id")
            .param("c", characterId).param("id", boxId).update()
        return characterId
    }

    fun markFailed(boxId: Long) {
        jdbc.sql("UPDATE gift_box SET status = 'FAILED' WHERE id = :id AND status = 'GENERATING'").param("id", boxId).update()
    }

    fun characters(memberId: Long): List<GiftCharacter> =
        jdbc.sql("SELECT * FROM gift_character WHERE member_id = :m ORDER BY id").param("m", memberId).query(characterMapper).list()

    fun character(id: Long): GiftCharacter? =
        jdbc.sql("SELECT * FROM gift_character WHERE id = :id").param("id", id).query(characterMapper).optional().orElse(null)

    fun setAvatar(memberId: Long, characterId: Long?): Int =
        jdbc.sql("UPDATE member SET avatar_character_id = :c WHERE id = :m").param("c", characterId).param("m", memberId).update()

    /** 이 사람이 담은 노래가 속한 구역 이름과 곡 수 (활성 지도 판, 많은 순). 캐릭터 프롬프트 재료 — 가수 이름은 들어가지 않는다 */
    /**
     * [until]을 주면 그 시각까지 담은 노래만 센다 (상자를 연 시각 기준으로 "이 캐릭터를 만든 재료"를 다시 보여 줄 때).
     * 그 뒤에 뺀 노래·바뀐 지도 판은 반영되지 않는 근사다 (따로 저장하지 않음)
     */
    fun genreMix(memberId: Long, limit: Int = 5, until: Instant? = null): List<Pair<String, Int>> =
        jdbc.sql(
            """
            SELECT g.name, count(*) FROM room_song s
            JOIN map_version v ON v.active
            JOIN artist_position p ON p.artist_id = s.artist_id AND p.map_version = v.id
            JOIN galaxy g ON g.id = p.galaxy_id
            WHERE s.member_id = :m AND g.name IS NOT NULL AND (CAST(:u AS timestamptz) IS NULL OR s.added_at <= :u)
            GROUP BY g.name ORDER BY 2 DESC, 1 LIMIT :l
            """.trimIndent(),
        ).param("m", memberId).param("l", limit).param("u", until?.let(Timestamp::from)).query { rs, _ -> rs.getString(1) to rs.getInt(2) }.list()

    /** 서비스 전체 오늘(한국 날짜) 이미지 생성 수 (llm_call GIFT_IMAGE) */
    fun imagesToday(today: LocalDate): Int =
        jdbc.sql("SELECT count(*) FROM llm_call WHERE purpose = 'GIFT_IMAGE' AND (created_at AT TIME ZONE 'Asia/Seoul')::date = :d")
            .param("d", today).query(Int::class.java).single()

    /** 완전 삭제할 방(숨긴 지 7일 지남)의 캐릭터 이미지 키 (S3에서 먼저 지운다, 기본 캐릭터 제외) */
    fun keysOfRoomsToPurge(now: Instant): List<String> =
        jdbc.sql(
            """
            SELECT c.s3_key FROM gift_character c JOIN member m ON m.id = c.member_id JOIN room r ON r.id = m.room_id
            WHERE r.status = 'HIDDEN' AND r.purge_at <= :now AND c.s3_key NOT LIKE 'preset:%'
            """.trimIndent(),
        ).param("now", Timestamp.from(now)).query { rs, _ -> rs.getString(1) }.list()
}
