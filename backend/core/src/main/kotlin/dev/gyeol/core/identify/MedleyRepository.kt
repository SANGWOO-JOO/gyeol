package dev.gyeol.core.identify

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class MedleySong(val artist: String?, val song: String, val trackId: Long? = null)

/** 메들리 영상(제목·채널)에 든 곡들. 다시 저장하면 바꾼다 */
@Repository
class MedleyRepository(private val jdbc: JdbcClient) {
    /** [categoryCheckedAt]: I-48 4-2 판정 시각. 이미 있던 행과 합친다 (판정 없는 근거가 있었으면 null 유지, 아니면 더 늦은 시각) */
    fun save(title: String, channel: String, songs: List<MedleySong>, categoryCheckedAt: java.time.Instant? = null) {
        val old = jdbc.sql("SELECT count(*), bool_or(category_checked_at IS NULL), max(category_checked_at) FROM medley_song WHERE title = :t AND channel = :c")
            .param("t", title).param("c", channel)
            .query { rs, _ -> Triple(rs.getInt(1), rs.getBoolean(2), rs.getTimestamp(3)?.toInstant()) }.single()
        val cat = mergeCategory(if (old.second) null else old.third, categoryCheckedAt, oldExists = old.first > 0)
        jdbc.sql("DELETE FROM medley_song WHERE title = :t AND channel = :c").param("t", title).param("c", channel).update()
        songs.forEachIndexed { i, s ->
            jdbc.sql("INSERT INTO medley_song (title, channel, position, artist, song, track_id, category_checked_at) VALUES (:t, :c, :p, :a, :s, :k, :cat)")
                .param("t", title).param("c", channel).param("p", i).param("a", s.artist).param("s", s.song).param("k", s.trackId)
                .param("cat", cat?.let(java.sql.Timestamp::from)).update()
        }
    }
}
