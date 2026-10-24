package dev.gyeol.fetcher.lastfm

import dev.gyeol.fetcher.TrackHit
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 33개 실제 관찰 표본(src/test/resources/lastfm-samples.json)으로 다시 정렬 규칙을 잰다.
 * "깔끔한 원곡" = 제목·가수 키워드가 맞고, 변형 표시가 없고, 가수 칸이 [unknown]·채널 이름이 아닌 항목.
 */
class RerankerTest {

    private data class Case(val type: String, val q: String, val title: List<String>, val artist: List<String>, val results: List<TrackHit>)

    private val cases: List<Case> = run {
        val root = JsonMapper.builder().build().readTree(javaClass.getResourceAsStream("/lastfm-samples.json"))
        root.path("cases").toList().map { c ->
            Case(
                type = c.path("type").asString(),
                q = c.path("q").asString(),
                title = c.path("title").toList().map { it.asString() },
                artist = c.path("artist").toList().map { it.asString() },
                results = c.path("results").toList().map { r -> TrackHit(title = r[0].asString(), artist = r[1].asString()) },
            )
        }
    }

    private fun isCleanOriginal(hit: TrackHit, c: Case): Boolean {
        val t = Reranker.norm(hit.title)
        val names = Reranker.artistNames(hit.artist) + Reranker.norm(hit.artist)
        return c.title.any { t.contains(Reranker.norm(it)) } &&
            c.artist.any { k -> names.any { it.contains(Reranker.norm(k)) } } &&
            !Reranker.isVariant(hit.title) &&
            !Reranker.isUnknownArtist(hit.artist) &&
            !Reranker.isChannelLike(hit.artist)
    }

    private fun countTop1(order: (Case) -> List<TrackHit>) = cases.count { c -> order(c).firstOrNull()?.let { isCleanOriginal(it, c) } == true }

    @Test
    fun `33개 표본에서 깔끔한 원곡 1위 비율이 오른다`() {
        val before = countTop1 { it.results }
        val after = countTop1 { Reranker.rerank(it.q, it.results, 10) }
        val reachable = cases.count { c -> c.results.any { isCleanOriginal(it, c) } }
        println("깔끔한 원곡 1위: 정렬 전 $before / 정렬 후 $after (후보 안에 깔끔한 원곡이 있는 표본 $reachable / 전체 ${cases.size})")

        cases.forEach { c ->
            val top = Reranker.rerank(c.q, c.results, 10).firstOrNull()
            println("  [${c.type}] ${c.q} → ${top?.let { "${it.title} / ${it.artist}" } ?: "(결과 없음)"}${if (top != null && isCleanOriginal(top, c)) "" else "  ✗"}")
        }

        assertTrue(after > before, "다시 정렬 후 원곡 1위가 늘어야 합니다 (전 $before, 후 $after)")
        assertEquals(reachable, after, "후보 안에 깔끔한 원곡이 있으면 모두 1위로 올라와야 합니다")
    }

    @Test
    fun `변형이 1위였던 사례들이 원곡으로 바뀐다`() {
        val expect = mapOf(
            "방탄소년단 봄날" to ("봄날" to "BTS (방탄소년단)"),
            "검정치마 Everything" to ("EVERYTHING" to "검정치마"),
            "YOASOBI 夜に駆ける" to ("夜に駆ける" to "YOASOBI"),
            "あいみょん マリーゴールド" to ("マリーゴールド" to "あいみょん"),
            "yoasobi yoru ni kakeru" to ("Yoru ni Kakeru" to "YOASOBI"),
            "The Weeknd Blinding Lights" to ("Blinding Lights" to "The Weeknd"),
            "트와이스 tt" to ("TT" to "트와이스"),
            "아이유 좋은날" to ("좋은날" to "아이유"),
            "Coldplay Yellow" to ("Yellow" to "Coldplay"),
            "Adele Hello" to ("Hello" to "Adele"),
            "宇多田ヒカル First Love" to ("First Love" to "宇多田ヒカル"),
        )
        expect.forEach { (q, want) ->
            val c = cases.first { it.q == q }
            val top = Reranker.rerank(c.q, c.results, 10).first()
            assertEquals(want, top.title to top.artist, "검색어: $q")
        }
    }

    @Test
    fun `제목 정리 - 앞 번호와 가수 머리말을 뗀다`() {
        assertEquals("거짓말", Reranker.cleanTitle("045. 빅뱅 - 거짓말", Reranker.norm("빅뱅 거짓말")))
        assertEquals("Yellow", Reranker.cleanTitle("Coldplay - Yellow", Reranker.norm("Coldplay Yellow")))
        // 검색어에 없는 머리말은 남긴다 (곡 제목의 일부일 수 있음)
        assertEquals("Love - Story", Reranker.cleanTitle("Love - Story", Reranker.norm("taylor")))
    }

    @Test
    fun `같은 곡은 하나로 합친다`() {
        val c = cases.first { it.q == "혁오 위잉위잉" }
        val out = Reranker.rerank(c.q, c.results, 10)
        assertEquals(1, out.size, "위잉위잉 / 혁오, 혁오 (Hyukoh), 혁오(Hyukoh)는 같은 곡")
    }
}
