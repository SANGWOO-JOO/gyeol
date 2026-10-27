package dev.gyeol.fetcher.knowledge

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 지식 청크 만들기 (L3-1). 외부 응답은 필요한 칸만 흉내 낸다 */
class KnowledgeServiceTest {
    private val json = JsonMapper.builder().build()
    private fun node(v: Any): JsonNode = json.valueToTree(v)
    private val wikidata = mapOf("type" to "wikidata", "url" to mapOf("resource" to "https://www.wikidata.org/wiki/Q123"))
    private fun rel(type: String, name: String, id: String = "11111111-1111-1111-1111-111111111111") = mapOf("type" to type, "artist" to mapOf("id" to id, "name" to name))

    private val mbResponses = mapOf(
        "/recording/r" to node(mapOf("relations" to listOf(rel("arranger", "김희원"), mapOf("type" to "performance", "work" to mapOf("id" to "w1"))), "tags" to listOf(mapOf("name" to "ballad", "count" to 3)))),
        "/work/w1" to node(mapOf("relations" to listOf(rel("composer", "김희원"), rel("lyricist", "IU"), wikidata))),
        "/artist/a" to node(mapOf("name" to "IU", "type" to "Person", "area" to mapOf("name" to "South Korea"), "genres" to listOf(mapOf("name" to "k-pop")), "relations" to listOf(wikidata))),
        "/release-group/g" to node(mapOf("title" to "밤편지", "primary-type" to "Single", "first-release-date" to "2017-03-24", "relations" to emptyList<Any>())),
    )
    private val lastfmCalls = mutableListOf<Pair<String, Map<String, String>>>()
    private val service = KnowledgeService(
        mb = { path, _ -> mbResponses.getValue(path) },
        sitelinks = { _, langs -> mapOf("ko" to "아이유", "en" to "IU (singer)", "ja" to "IU").filterKeys { it in langs } },
        wiki = { lang, title -> if (lang == "ja") null else WikiSummary(lang, title, "$title 요약 문장입니다.", "https://$lang.wikipedia.org/wiki/$title") },
        lastfm = { method, params ->
            lastfmCalls += method to params
            if (method == "artist.getInfo") node(mapOf("artist" to mapOf("url" to "https://www.last.fm/music/IU", "bio" to mapOf("summary" to "IU is a singer. <a href=\"x\">Read more on Last.fm</a>"), "tags" to mapOf("tag" to listOf(mapOf("name" to "k-pop"))))))
            else null
        },
    )

    @Test
    fun `녹음 - 크레딧·작품·태그를 MusicBrainz 문장 청크로`() {
        val r = service.recording("r", "아이유", "밤편지")
        assertEquals(listOf("w1"), r.workIds)
        assertEquals(listOf("ARRANGER" to "김희원"), r.credits.map { it.role to it.name })
        assertEquals("녹음 (MusicBrainz): 편곡 김희원; 장르·태그 ballad.", r.chunks.single().text)
        assertEquals("CC0", r.chunks.single().license)
        assertEquals("track.getInfo" to mapOf("artist" to "아이유", "track" to "밤편지"), lastfmCalls.single()) // 사용자가 본 이름으로 조회
    }

    @Test
    fun `작품 - 작곡·작사 크레딧과 위키데이터로 찾은 Wikipedia 요약 (문서가 없는 언어는 뺀다)`() {
        val r = service.work("w1")
        assertEquals(setOf("COMPOSER" to "김희원", "LYRICIST" to "IU"), r.credits.map { it.role to it.name }.toSet())
        assertEquals(listOf("MUSICBRAINZ/ko", "WIKIPEDIA/ko", "WIKIPEDIA/en"), r.chunks.map { "${it.source}/${it.lang}" })
        assertTrue(r.chunks.first().text.contains("작곡 김희원") && r.chunks.first().text.contains("작사 IU"))
        assertEquals("CC BY-SA 4.0", r.chunks[1].license)
    }

    @Test
    fun `가수 - 사실·장르, Wikipedia, Last·fm 소개 (HTML과 출처 링크 문구 제거)`() {
        val r = service.artist("a", "IU")
        assertEquals("가수 'IU' (MusicBrainz): 유형 Person; 지역 South Korea; 장르·태그 k-pop.", r.chunks.first().text)
        val lastfm = r.chunks.single { it.source == "LASTFM" }
        assertEquals("Last.fm 태그: k-pop. IU is a singer.", lastfm.text)
        assertEquals("https://www.last.fm/music/IU", lastfm.url)
    }

    @Test
    fun `음반 - 종류·발매일만 있어도 청크, 위키데이터가 없으면 Wikipedia 없음`() {
        val r = service.releaseGroup("g", "IU", "밤편지")
        assertEquals(listOf("음반 '밤편지' (MusicBrainz): 종류 Single; 처음 발매 2017-03-24."), r.chunks.map { it.text })
    }

    @Test
    fun `길이 제한은 문장 단위`() {
        val text = "첫 문장입니다. " + "가".repeat(30) + "다. 마지막 문장은 잘려야 한다."
        assertEquals("첫 문장입니다. ${"가".repeat(30)}다.", Chunks.limit(text, 50))
        assertEquals("짧다", Chunks.limit("짧다", 50))
        assertEquals("a & \"b\"", Chunks.clean("<p>a &amp; &quot;b&quot;</p>"))
    }
}
