package dev.gyeol.core.identify

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class MedleySong(val artist: String?, val song: String, val trackId: Long? = null)

/** 메들리 영상(제목·채널)에 든 곡들. 다시 저장하면 바꾼다 */
@Repository
class MedleyRepository(private val jdbc: JdbcClient) {
    fun save(title: String, channel: String, songs: List<MedleySong>) {
        jdbc.sql("DELETE FROM medley_song WHERE title = :t AND channel = :c").param("t", title).param("c", channel).update()
        songs.forEachIndexed { i, s ->
            jdbc.sql("INSERT INTO medley_song (title, channel, position, artist, song, track_id) VALUES (:t, :c, :p, :a, :s, :k)")
                .param("t", title).param("c", channel).param("p", i).param("a", s.artist).param("s", s.song).param("k", s.trackId).update()
        }
    }
}
