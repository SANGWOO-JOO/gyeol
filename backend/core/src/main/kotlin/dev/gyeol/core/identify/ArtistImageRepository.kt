package dev.gyeol.core.identify

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class ArtistImageTarget(val artistId: Long, val mbid: String, val videoId: String?)

/** 가수 기록 없이 Topic 채널로만 들은 가수 ("Red Velvet - Topic") */
/** [titles]: 그 채널에서 많이 들은 곡 제목 (같은 이름 가수가 여럿일 때 확인용) */
data class ChannelImageTarget(val channel: String, val videoId: String?, val titles: List<String> = emptyList())

data class ArtistImage(val source: String, val s3Key: String? = null, val url: String? = null, val pageUrl: String? = null, val author: String? = null, val license: String? = null)

@Repository
class ArtistImageRepository(private val jdbc: JdbcClient) {

    /** 세션에서 많이 들은 가수 중 이미지를 아직 찾지 않았거나, YouTube·없음 결과가 [retryDays]보다 오래된 가수. Topic 영상 하나를 함께 */
    fun targets(sessionId: Long, top: Int, retryDays: Int): List<ArtistImageTarget> =
        jdbc.sql(
            """
            WITH top AS (
                SELECT t.artist_id, count(*) AS plays,
                       min(l.video_id) FILTER (WHERE l.channel LIKE '% - Topic' AND l.video_id IS NOT NULL) AS video_id
                FROM listen_event l JOIN track t ON t.id = l.track_id
                WHERE l.session_id = :sid
                GROUP BY t.artist_id ORDER BY plays DESC LIMIT :top
            )
            SELECT a.id, a.mbid::text, top.video_id
            FROM top JOIN artist a ON a.id = top.artist_id
            LEFT JOIN artist_image ai ON ai.artist_id = a.id
            WHERE ai.artist_id IS NULL OR (ai.source <> 'COMMONS' AND ai.checked_at < now() - make_interval(days => :days))
            ORDER BY top.plays DESC
            """.trimIndent(),
        ).param("sid", sessionId).param("top", top).param("days", retryDays)
            .query { rs, _ -> ArtistImageTarget(rs.getLong(1), rs.getString(2), rs.getString(3)) }.list()

    /** 세션에서 많이 들은 Topic 채널 중 어떤 재생도 가수 기록에 연결되지 않은 채널. 재확인 규칙은 [targets]와 같다 */
    fun channelTargets(sessionId: Long, top: Int, retryDays: Int): List<ChannelImageTarget> =
        jdbc.sql(
            """
            WITH ch AS (
                SELECT l.channel, count(*) AS plays, min(l.video_id) AS video_id, bool_or(t.artist_id IS NOT NULL) AS has_artist
                FROM listen_event l LEFT JOIN track t ON t.id = l.track_id
                WHERE l.session_id = :sid AND l.channel LIKE '% - Topic'
                GROUP BY l.channel
            )
            SELECT ch.channel, ch.video_id,
                   ARRAY(SELECT l2.title FROM listen_event l2 WHERE l2.session_id = :sid AND l2.channel = ch.channel
                         GROUP BY l2.title ORDER BY count(*) DESC, l2.title LIMIT 3) AS titles
            FROM ch LEFT JOIN channel_image ci ON ci.channel = ch.channel
            WHERE NOT ch.has_artist
              AND (ci.channel IS NULL OR (ci.source <> 'COMMONS' AND ci.checked_at < now() - make_interval(days => :days)))
            ORDER BY ch.plays DESC LIMIT :top
            """.trimIndent(),
        ).param("sid", sessionId).param("top", top).param("days", retryDays)
            .query { rs, _ -> ChannelImageTarget(rs.getString(1), rs.getString(2), (rs.getArray(3).array as Array<*>).map { it.toString() }) }.list()

    /** 채널 행을 만들거나 찾아 id를 돌려준다 (S3 키에 쓴다) */
    fun channelId(channel: String): Long =
        jdbc.sql(
            """
            INSERT INTO channel_image (channel, source) VALUES (:c, 'NONE')
            ON CONFLICT (channel) DO UPDATE SET channel = EXCLUDED.channel RETURNING id
            """.trimIndent(),
        ).param("c", channel).query(Long::class.java).single()

    fun saveChannel(channel: String, artistMbid: java.util.UUID?, image: ArtistImage) {
        jdbc.sql(
            """
            INSERT INTO channel_image (channel, artist_mbid, source, s3_key, url, page_url, author, license) VALUES (:c, :m, :s, :k, :u, :p, :au, :l)
            ON CONFLICT (channel) DO UPDATE SET artist_mbid = EXCLUDED.artist_mbid, source = EXCLUDED.source, s3_key = EXCLUDED.s3_key, url = EXCLUDED.url,
                page_url = EXCLUDED.page_url, author = EXCLUDED.author, license = EXCLUDED.license, checked_at = now()
            """.trimIndent(),
        ).param("c", channel).param("m", artistMbid).param("s", image.source).param("k", image.s3Key).param("u", image.url)
            .param("p", image.pageUrl).param("au", image.author).param("l", image.license).update()
    }

    /** 직접 지정으로 Commons를 건너뛸 가수인지 (V20 artist_image_rule) */
    fun skipCommons(mbid: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM artist_image_rule WHERE artist_mbid = :m::uuid AND skip_commons)")
            .param("m", mbid).query(Boolean::class.java).single()

    fun save(artistId: Long, image: ArtistImage) {
        jdbc.sql(
            """
            INSERT INTO artist_image (artist_id, source, s3_key, url, page_url, author, license) VALUES (:a, :s, :k, :u, :p, :au, :l)
            ON CONFLICT (artist_id) DO UPDATE SET source = EXCLUDED.source, s3_key = EXCLUDED.s3_key, url = EXCLUDED.url,
                page_url = EXCLUDED.page_url, author = EXCLUDED.author, license = EXCLUDED.license, checked_at = now()
            """.trimIndent(),
        ).param("a", artistId).param("s", image.source).param("k", image.s3Key).param("u", image.url)
            .param("p", image.pageUrl).param("au", image.author).param("l", image.license).update()
    }
}
