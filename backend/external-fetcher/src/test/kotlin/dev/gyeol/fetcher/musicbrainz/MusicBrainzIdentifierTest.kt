package dev.gyeol.fetcher.musicbrainz

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 곡 식별 규칙 (docs/l1-design.md 4-3, docs/l2-design.md). MusicBrainz 응답은 필요한 칸만 흉내 낸다.
 * 실제 API 정확도는 tools/identify-eval/eval.mjs로 잰다 (37곡 표본, 확실한 29곡 중 28곡).
 */
class MusicBrainzIdentifierTest {
    private val json = JsonMapper.builder().build()

    private fun artist(id: String, name: String, score: Int = 100, aliases: List<String> = emptyList()) =
        mapOf("id" to id, "name" to name, "sort-name" to name, "score" to score, "aliases" to aliases.map { mapOf("name" to it) })

    private fun release(title: String, date: String, type: String = "Album", secondary: List<String> = emptyList(), status: String = "Official", id: String = "rel-$title-$date") =
        mapOf("id" to id, "title" to title, "date" to date, "status" to status, "release-group" to mapOf("id" to "rg-$title", "primary-type" to type, "secondary-types" to secondary))

    private fun recording(id: String, title: String, vararg releases: Map<String, Any>, disambiguation: String = "") =
        mapOf("id" to id, "title" to title, "disambiguation" to disambiguation, "releases" to releases.toList())

    /** 가짜 MusicBrainz: 가수 검색, 제목 검색, 가수 녹음 목록을 각각 정해 둔다 */
    private fun identifier(
        artists: List<Map<String, Any>>,
        byTitle: Map<String, List<Map<String, Any>>> = emptyMap(),
        byArtist: List<Map<String, Any>> = emptyList(),
        spellings: List<String> = emptyList(),
        calls: MutableList<String> = mutableListOf(),
    ) = MusicBrainzIdentifier(
        mb = { path, params ->
            val q = params["query"].toString()
            calls += "$path $q"
            val body: Any = when {
                path == "/artist" -> mapOf("artists" to artists)
                q.contains("recording:(") -> mapOf("recordings" to (byTitle[q.substringAfter("recording:(").substringBefore(")")] ?: emptyList()))
                else -> mapOf("recordings" to if ((params["offset"] as Int) == 0) byArtist else emptyList())
            }
            json.valueToTree<JsonNode>(body)
        },
        spellings = { _, _ -> spellings },
    )

    @Test
    fun `가장 이른 공식 음반을 고른다, 컴필레이션·비공식은 뺀다`() {
        val r = identifier(
            listOf(artist("a1", "IU", aliases = listOf("아이유"))),
            mapOf(
                "밤편지" to listOf(
                    recording(
                        "r1", "밤편지 (Through the Night)",
                        release("팔레트", "2017-04-21"),
                        release("밤편지", "2017-03-24", type = "Single"),
                        release("히트곡 모음", "2016-01-01", secondary = listOf("Compilation")),
                        release("부트렉", "2015-01-01", status = "Bootleg"),
                    ),
                ),
            ),
        ).identify("아이유", "밤편지")
        assertEquals("MATCHED", r.status)
        assertEquals("IU", r.artist!!.name)
        assertEquals("밤편지", r.release!!.title)
        assertEquals("2017-03-24", r.release!!.date)
        assertEquals("Single", r.release!!.type)
    }

    @Test
    fun `이름이 정확히 같은 가수만 - 점수가 높아도 다른 가수는 버린다`() {
        val r = identifier(listOf(artist("x", "Crushed", score = 100), artist("a", "Crush", score = 90, aliases = listOf("크러쉬")))).identify("크러쉬", "Beautiful")
        assertEquals("NOT_FOUND", r.status)
        assertEquals("RECORDING", r.reason) // "Crush"는 가수로 확정, 이 가짜 응답에는 녹음이 없음
        assertEquals("ARTIST", identifier(listOf(artist("x", "Crushed"))).identify("크러쉬", "Beautiful").reason)
    }

    @Test
    fun `리믹스·라이브 녹음은 원곡 녹음보다 뒤로 - 더 이른 음반이어도`() {
        val r = identifier(
            listOf(artist("c", "Coldplay")),
            mapOf(
                "Yellow" to listOf(
                    recording("remix", "Yellow (The Alpha remix)", release("Remixes", "1999-01-01")),
                    recording("live", "Yellow", release("Live Show", "1999-06-01"), disambiguation = "live, 2000-06-01"),
                    recording("orig", "Yellow", release("Yellow", "2000-06-26", type = "Single")),
                ),
            ),
        ).identify("Coldplay", "Yellow")
        assertEquals("orig", r.recording!!.mbid)
        assertEquals("2000-06-26", r.release!!.date)
    }

    @Test
    fun `언어판 설명(Korean version)은 원곡으로 본다, 같은 날이면 앨범·EP를 싱글보다 먼저`() {
        val r = identifier(
            listOf(artist("b", "BLACKPINK", aliases = listOf("블랙핑크"))),
            mapOf(
                "뚜두뚜두" to listOf(
                    recording(
                        "kr", "뚜두뚜두 (DDU-DU DDU-DU)",
                        release("뚜두뚜두 (DDU-DU DDU-DU)", "2018-06-15", type = "Other"),
                        release("SQUARE UP", "2018-06-15", type = "EP"),
                        disambiguation = "Korean version",
                    ),
                ),
            ),
        ).identify("블랙핑크", "뚜두뚜두")
        assertEquals("SQUARE UP", r.release!!.title)
    }

    @Test
    fun `버전 음반(ver·inst)은 다른 선택지가 없을 때만`() {
        val r = identifier(
            listOf(artist("n", "NewJeans", aliases = listOf("뉴진스"))),
            mapOf("Hype Boy" to listOf(recording("h", "Hype Boy", release("Hype Boy (DANIELLE&HAERIN ver.)", "2022-07-23", type = "Single"), release("New Jeans", "2022-08-01", type = "EP")))),
        ).identify("뉴진스", "Hype Boy")
        assertEquals("New Jeans", r.release!!.title)
    }

    @Test
    fun `제목 검색에 없으면 Last·fm 표기로, 그래도 없으면 가수 녹음을 훑는다 - 로마자 제목도 같은 곡`() {
        val spaced = identifier(
            listOf(artist("iu", "IU", aliases = listOf("아이유"))),
            mapOf("좋은 날" to listOf(recording("g", "좋은 날", release("REAL", "2010-12-09", type = "EP")))),
            spellings = listOf("좋은 날"),
        ).identify("아이유", "좋은날")
        assertEquals("REAL", spaced.release!!.title)

        val calls = mutableListOf<String>()
        val roman = identifier(
            listOf(artist("s", "SE SO NEON", aliases = listOf("새소년"))),
            byArtist = listOf(recording("n", "NAN CHUN", release("NAN CHUN", "2020-05-10", type = "Single"))),
            calls = calls,
        ).identify("새소년", "난춘")
        assertEquals("NAN CHUN", roman.release!!.title)
        assertTrue(calls.last().endsWith("arid:s"), "마지막 단계는 가수 녹음 훑기")
    }

    @Test
    fun `녹음은 있는데 쓸 만한 음반이 없으면 RELEASE`() {
        val r = identifier(
            listOf(artist("z", "ZARD")),
            mapOf("負けないで" to listOf(recording("m", "負けないで", release("Best", "2000-01-01", secondary = listOf("Compilation"))))),
        ).identify("ZARD", "負けないで")
        assertEquals("RELEASE", r.reason)
        assertNull(r.release)
    }

    @Test
    fun `제목 비교 - 괄호·슬래시·로마자`() {
        assertTrue(TitleMatch.sameTitle("뚜두뚜두 (DDU-DU DDU-DU)", "뚜두뚜두"))
        assertTrue(TitleMatch.sameTitle("Everything / 걱정하지마", "Everything"))
        assertTrue(TitleMatch.sameTitle("NAN CHUN", "난춘"))
        assertTrue(TitleMatch.sameTitle("좋은 날", "좋은날"))
        assertFalse(TitleMatch.sameTitle("Yellow Submarine", "Yellow"))
        assertEquals("nanchun", TitleMatch.roman("난춘"))
    }
}
