package dev.gyeol.fetcher.artistimage

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistImageParserTest {
    private val json = JsonMapper.builder().build()

    @Test
    fun `MusicBrainz → Wikidata → Commons 응답에서 사진과 저작자·라이선스를 뽑는다`() {
        val artist = json.readTree("""{"relations":[{"type":"official homepage","url":{"resource":"https://x"}},{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q235613"}}]}""")
        assertEquals("Q235613", ArtistImageParser.wikidataId(artist))
        val entity = json.readTree("""{"entities":{"Q235613":{"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"Younha.jpg"}}}]}}}}""")
        assertEquals("Younha.jpg", ArtistImageParser.imageFile(entity, "Q235613"))
        val info = json.readTree(
            """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/a.jpg","descriptionurl":"https://commons.wikimedia.org/wiki/File:Younha.jpg",
               "extmetadata":{"Artist":{"value":"<a href='x'>티비텐 TV10</a>"},"LicenseShortName":{"value":"CC BY 3.0"}}}]}}}}""",
        )
        val photo = ArtistImageParser.photo(info)!!
        assertEquals("https://upload.wikimedia.org/a.jpg", photo.url)
        assertEquals("티비텐 TV10", photo.author)
        assertEquals("CC BY 3.0", photo.license)
    }

    @Test
    fun `가로로 긴 사진은 짧은 쪽이 250px가 되게 넓게 받는다`() {
        assertEquals(250, ArtistImageClient.thumbWidthOf(800, 1200)) // 세로 사진
        assertEquals(250, ArtistImageClient.thumbWidthOf(1000, 1000))
        assertEquals(801, ArtistImageClient.thumbWidthOf(4000, 1248)) // 5명 가로 사진 (Buzz)
        assertEquals(1000, ArtistImageClient.thumbWidthOf(9000, 1000)) // 상한
        assertEquals(4000 to 1248, ArtistImageParser.size(json.readTree("""{"query":{"pages":{"1":{"imageinfo":[{"width":4000,"height":1248}]}}}}""")))
    }

    @Test
    fun `채널 이름의 표기와 가장 많이 맞는 가수들만 후보로 남긴다`() {
        val search = json.readTree(
            """{"artists":[
                {"id":"god-us","name":"God","sort-name":"God","aliases":[]},
                {"id":"god-kr","name":"god","sort-name":"god","aliases":[{"name":"지오디","sort-name":"지오디"}]},
                {"id":"other","name":"Godsmack","sort-name":"Godsmack"}]}""",
        )
        assertEquals(listOf("god-kr"), ArtistImageParser.candidates(search, listOf("지오디 (god)", "지오디", "god")))
        // 표기 하나만 맞는 같은 이름 가수가 둘이면 둘 다 후보 (들은 곡으로 확인)
        assertEquals(listOf("god-us", "god-kr"), ArtistImageParser.candidates(search, listOf("god")))
        assertEquals(emptyList(), ArtistImageParser.candidates(json.readTree("""{"artists":[{"id":"x","name":"Release Party"}]}"""), listOf("Release")))
    }

    @Test
    fun `들은 곡이 녹음 목록에 있는지`() {
        val recs = json.readTree("""{"recordings":[{"title":"A"},{"title":"Mach"}]}""")
        assertTrue(ArtistImageParser.hasRecording(recs, "A"))
        assertFalse(ArtistImageParser.hasRecording(recs, "Kill the King"))
    }

    @Test
    fun `연결이나 사진이 없으면 null`() {
        assertNull(ArtistImageParser.wikidataId(json.readTree("""{"relations":[]}""")))
        assertNull(ArtistImageParser.imageFile(json.readTree("""{"entities":{"Q1":{"claims":{}}}}"""), "Q1"))
        assertNull(ArtistImageParser.photo(json.readTree("""{"query":{"pages":{"-1":{"missing":""}}}}""")))
    }

    @Test
    fun `I-50 위키 대표 이미지 - sitelinks는 ko·ja·en 순서, pageprops의 page_image_free`() {
        val entity = json.readTree("""{"entities":{"Q1":{"sitelinks":{"enwiki":{"title":"Hum (band)"},"jawiki":{"title":"ハム (バンド)"},"frwiki":{"title":"Hum"}}}}}""")
        assertEquals(listOf("jawiki" to "ハム (バンド)", "enwiki" to "Hum (band)"), ArtistImageParser.wikiTitles(entity, "Q1"))
        assertEquals(emptyList(), ArtistImageParser.wikiTitles(json.readTree("""{"entities":{"Q2":{}}}"""), "Q2"))
        val res = json.readTree("""{"query":{"pages":{"1578200":{"title":"Hum (band)","pageprops":{"page_image_free":"Hum_2026.png"}}}}}""")
        assertEquals("Hum_2026.png", ArtistImageParser.pageImageFree(res))
        assertNull(ArtistImageParser.pageImageFree(json.readTree("""{"query":{"pages":{"1":{"title":"x"}}}}""")))
    }

    @Test
    fun `I-50 거르기 - 로고(파일명·svg·분류)와 본인이 아닌 그림(벽화·그래피티·포스터·앨범 커버·서명)`() {
        fun info(cats: String) = json.readTree("""{"query":{"pages":{"1":{"imageinfo":[{"extmetadata":{"Categories":{"value":"$cats"}}}]}}}}""")
        val none = info("Musicians from Japan")
        // 관리 세션 표본 (11-14)
        assertEquals("LOGO", ArtistImageParser.rejectReason("Maisondes_PNG_logo.png", none))
        assertEquals("LOGO", ArtistImageParser.rejectReason("ILiFE!_Logo.png", none))
        assertEquals("LOGO", ArtistImageParser.rejectReason("Yorushika_logo.svg", none))
        assertEquals("LOGO", ArtistImageParser.rejectReason("Logo_of_Iyowa.webp", none))
        assertEquals("LOGO", ArtistImageParser.rejectReason("Band_mark.png", info("PD textlogo|SVG logos of Japan")))
        assertEquals("LOGO", ArtistImageParser.rejectReason("밴드_로고.png", none))
        assertEquals("NOT_SELF", ArtistImageParser.rejectReason("Граффитти_Jeff_Buckley._Revisited._Россия,_Октябрьский,_2015.jpg", none))
        assertEquals("NOT_SELF", ArtistImageParser.rejectReason("Wall_in_Seoul.jpg", info("Murals in Seoul")))
        assertEquals("NOT_SELF", ArtistImageParser.rejectReason("X_album_cover.jpg", none))
        assertEquals("NOT_SELF", ArtistImageParser.rejectReason("X_signature.png", none))
        assertEquals("NOT_SELF", ArtistImageParser.rejectReason("Concert_poster_2019.jpg", none))
        // 본인 사진
        assertNull(ArtistImageParser.rejectReason("Southern_All_Stars,_2023.png", none))
        assertNull(ArtistImageParser.rejectReason("That_Handsome_Devil_Performing_in_Ottawa.jpg", none))
        assertNull(ArtistImageParser.rejectReason("Hum_2026.png", null))
    }
}
