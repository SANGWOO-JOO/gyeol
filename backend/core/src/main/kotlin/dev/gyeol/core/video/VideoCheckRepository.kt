package dev.gyeol.core.video

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * 일반 영상 음악 판정 캐시 (L2-3b). 영상 ID → YouTube categoryId (10 = Music).
 * YouTube API 정책상 30일 안에 갱신하거나 지워야 하므로, [fresh]는 [RETENTION] 안의 기록만 보고 정리 작업이 오래된 기록을 지운다.
 */
@Repository
class VideoCheckRepository(private val jdbc: JdbcClient) {

    /** 영상 ID → categoryId (찾지 못한 영상은 빈 문자열). [since] 이후 기록만 */
    fun fresh(ids: Collection<String>, since: Instant): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql("SELECT video_id, coalesce(category_id, '') FROM video_check WHERE video_id IN (:ids) AND checked_at > :since")
            .param("ids", ids).param("since", Timestamp.from(since))
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()
    }

    /** 판정 결과 저장. [categories]에 없는 ID는 찾지 못한 영상으로 기록 (다시 묻지 않게) */
    fun save(asked: Collection<String>, categories: Map<String, String>) {
        asked.forEach { id ->
            jdbc.sql(
                """
                INSERT INTO video_check (video_id, category_id, checked_at) VALUES (:id, :cat, now())
                ON CONFLICT (video_id) DO UPDATE SET category_id = EXCLUDED.category_id, checked_at = now()
                """.trimIndent(),
            ).param("id", id).param("cat", categories[id]?.ifEmpty { null }).update()
        }
    }

    fun deleteOlderThan(before: Instant): Int =
        jdbc.sql("DELETE FROM video_check WHERE checked_at < :b").param("b", Timestamp.from(before)).update()

    companion object {
        /**
         * 판정 결과 보관 기간 (조회·정리 공용). YouTube API 정책(III.E.4)은 30일 안에 갱신하거나 지우라고 한다.
         * 정리 작업은 하루 한 번(04:00) 돌므로 30일로 두면 행이 최대 약 31일 남는다. 하루 여유를 둬 29일로 한다 (I-48, 11-14)
         */
        val RETENTION: Duration = Duration.ofDays(29)
    }
}
