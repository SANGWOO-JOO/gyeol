package dev.gyeol.fetcher.artistimage

import dev.gyeol.fetcher.FetcherProperties
import dev.gyeol.fetcher.RateGate
import dev.gyeol.fetcher.UpstreamException
import dev.gyeol.fetcher.coverart.CoverImage
import dev.gyeol.fetcher.musicbrainz.MusicBrainzClient
import dev.gyeol.fetcher.musicbrainz.TitleMatch
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Wikimedia Commons의 가수 사진. 재사용 조건(CC BY·BY-SA)에 따라 저작자·라이선스를 함께 돌려준다 */
data class ArtistPhoto(val url: String, val pageUrl: String?, val author: String?, val license: String?, val via: String? = null)

/** 응답 해석만 (외부 호출 없이 시험한다) */
object ArtistImageParser {
    private val TAG = Regex("<[^>]+>")

    /** MusicBrainz 가수의 Wikidata 항목 ID (Q…) */
    fun wikidataId(artist: JsonNode): String? =
        artist.path("relations").toList().firstOrNull { it.path("type").asString("") == "wikidata" }
            ?.path("url")?.path("resource")?.asString("")?.substringAfterLast('/')?.takeIf { it.startsWith("Q") }

    /** Wikidata 대표 이미지(P18)의 Commons 파일 이름 */
    fun imageFile(entity: JsonNode, qid: String): String? =
        entity.path("entities").path(qid).path("claims").path("P18").toList().firstOrNull()
            ?.path("mainsnak")?.path("datavalue")?.path("value")?.asString("")?.ifEmpty { null }

    /**
     * 이름 검색 결과에서 후보 가수들. [parts]는 채널 이름의 표기들 ("지오디 (god)" → 지오디, god).
     * 이름·정렬 이름·별칭이 표기와 정확히 같은 가수만 보고, 가장 많은 표기와 맞는 가수들만 남긴다
     * (지오디·god 둘 다 맞는 한국 그룹 god만 남는다). 하나면 그 가수, 여럿이면 들은 곡으로 확인한다 ([ArtistImageClient.findArtist])
     */
    fun candidates(search: JsonNode, parts: List<String>): List<String> {
        val wanted = parts.map(TitleMatch::norm).filter { it.isNotEmpty() }.toSet()
        val scored = search.path("artists").toList().map { a ->
            val names = (listOf(a.path("name").asString(""), a.path("sort-name").asString("")) +
                a.path("aliases").toList().flatMap { listOf(it.path("name").asString(""), it.path("sort-name").asString("")) })
                .map(TitleMatch::norm).filter { it.isNotEmpty() }.toSet()
            a.path("id").asString("") to wanted.count { it in names }
        }.filter { it.second > 0 && it.first.isNotEmpty() }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }.distinct()
    }

    /** I-50: Wikidata sitelinks 중 위키백과 문서 제목, [WIKIS] 순서 (kowiki → jawiki → enwiki) */
    fun wikiTitles(entity: JsonNode, qid: String): List<Pair<String, String>> {
        val links = entity.path("entities").path(qid).path("sitelinks")
        return WIKIS.mapNotNull { w -> links.path(w).path("title").asString("").ifEmpty { null }?.let { w to it } }
    }

    /** 위키백과 pageprops의 자유 라이선스 대표 이미지 파일 이름 (page_image_free) */
    fun pageImageFree(res: JsonNode): String? =
        res.path("query").path("pages").toList().firstNotNullOfOrNull { it.path("pageprops").path("page_image_free").asString("").ifEmpty { null } }

    /**
     * I-50 위키 대표 이미지 거르기. 본인 사진이 아니면 이유, 쓸 수 있으면 null.
     * LOGO: 파일명에 logo·ロゴ·로고, .svg, Commons 분류에 logo. NOT_SELF: 벽화·그래피티·포스터·앨범 커버·서명
     */
    fun rejectReason(file: String, info: JsonNode?): String? {
        val name = file.lowercase()
        val cats = info?.path("query")?.path("pages")?.toList()?.firstOrNull()?.path("imageinfo")?.toList()?.firstOrNull()
            ?.path("extmetadata")?.path("Categories")?.path("value")?.asString("")?.lowercase().orEmpty()
        if (LOGO.containsMatchIn(name) || name.endsWith(".svg") || cats.contains("logo")) return "LOGO"
        if (NOT_SELF.containsMatchIn(name) || NOT_SELF_CATS.containsMatchIn(cats)) return "NOT_SELF"
        return null
    }

    val WIKIS = listOf("kowiki", "jawiki", "enwiki")
    private val LOGO = Regex("""logo|ロゴ|로고""")
    private val NOT_SELF = Regex("""graffiti|граффит|mural|벽화|그래피티|グラフィティ|壁画|poster|포스터|ポスター|album[ _-]?cover|cover[ _-]?art|앨범[ _]?커버|ジャケット|signature|autograph|서명|署名""")
    private val NOT_SELF_CATS = Regex("""graffiti|murals|posters|album covers|signatures|autographs""")

    /** 녹음 검색 결과에 [title]과 같은 곡이 있는지 */
    fun hasRecording(search: JsonNode, title: String): Boolean =
        search.path("recordings").toList().any { TitleMatch.sameTitle(it.path("title").asString(""), title) }

    /** Commons 원본 크기 (너비, 높이) */
    fun size(info: JsonNode): Pair<Int, Int>? {
        val ii = info.path("query").path("pages").toList().firstOrNull()?.path("imageinfo")?.toList()?.firstOrNull() ?: return null
        val w = ii.path("width").asInt(0)
        val h = ii.path("height").asInt(0)
        return if (w > 0 && h > 0) w to h else null
    }

    /** Commons imageinfo 응답 → 250px 썸네일 주소와 저작자·라이선스 */
    fun photo(info: JsonNode): ArtistPhoto? {
        val page = info.path("query").path("pages").toList().firstOrNull() ?: return null
        val ii = page.path("imageinfo").toList().firstOrNull() ?: return null
        val url = ii.path("thumburl").asString("").ifEmpty { ii.path("url").asString("") }.ifEmpty { return null }
        val meta = ii.path("extmetadata")
        fun text(k: String) = meta.path(k).path("value").asString("").replace(TAG, "").trim().ifEmpty { null }
        return ArtistPhoto(url, ii.path("descriptionurl").asString("").ifEmpty { null }, text("Artist")?.take(120), text("LicenseShortName"))
    }
}

/**
 * 가수 사진 (docs/todo/artist-images.md): MusicBrainz → Wikidata(P18) → Wikimedia Commons.
 * P18이 없으면 위키백과 ko → ja → en 문서의 자유 라이선스 대표 이미지(Commons 파일, I-50)
 * MusicBrainz 호출은 식별과 같은 속도 제한을 쓰고, Wikidata·Commons는 따로 천천히 부른다.
 */
@Component
class ArtistImageClient(private val props: FetcherProperties, private val musicBrainz: MusicBrainzClient) {
    private val gate = RateGate(Duration.ofMillis(500), Duration.ofSeconds(60))
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
    private val json = JsonMapper.builder().build()
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * 가수 기록이 없는 Topic 채널 이름으로 MusicBrainz 가수를 찾는다. 확실하지 않으면 null.
     * 같은 이름이 여럿이면 그 채널에서 들은 곡([titles])이 녹음 목록에 있는 후보가 하나뿐일 때만 고른다
     * (검색 점수로 고르면 RAINBOW가 영국 록 밴드로 연결됐다, 2026-11-10)
     */
    fun findArtist(name: String, titles: List<String>): String? {
        val parts = (TitleMatch.searchParts(name) + name).distinctBy(TitleMatch::norm)
        val query = parts.joinToString(" OR ") { p -> "artist:\"${quote(p)}\" OR alias:\"${quote(p)}\"" }
        val ids = ArtistImageParser.candidates(musicBrainz.fetch("/artist", mapOf("query" to query, "limit" to 25)), parts)
        if (ids.size <= 1) return ids.singleOrNull()
        val songs = titles.filter { it.isNotBlank() }.take(MAX_TITLES)
        val verified = ids.take(MAX_CANDIDATES).filter { id ->
            songs.any { t -> ArtistImageParser.hasRecording(musicBrainz.fetch("/recording", mapOf("query" to "arid:$id AND recording:\"${quote(t)}\"", "limit" to 5)), t) }
        }
        return verified.singleOrNull()
    }

    private fun quote(s: String) = s.replace("\\", "").replace("\"", "")

    fun commons(mbid: String): ArtistPhoto? {
        val artist = musicBrainz.fetch("/artist/$mbid", mapOf("inc" to "url-rels"))
        val qid = ArtistImageParser.wikidataId(artist) ?: return null
        val entity = getJson("https://www.wikidata.org/wiki/Special:EntityData/$qid.json") ?: return null
        val file = ArtistImageParser.imageFile(entity, qid) ?: return wikiImage(mbid, entity, qid)
        return photoOf(file)?.copy(via = "P18")
    }

    /**
     * I-50 2단계: P18이 없으면 위키백과(ko → ja → en) 문서의 자유 라이선스 대표 이미지.
     * Commons에 있는 파일만 쓰고(로컬 파일은 imageinfo가 없어 빠진다), 로고·본인이 아닌 그림은 거르고 다음 위키를 본다
     */
    private fun wikiImage(mbid: String, entity: JsonNode, qid: String): ArtistPhoto? {
        val seen = mutableSetOf<String>() // 위키마다 같은 파일이 흔하다: 한 번 본 파일은 다시 조회하지 않는다
        for ((wiki, page) in ArtistImageParser.wikiTitles(entity, qid)) {
            val lang = wiki.removeSuffix("wiki")
            val t = URLEncoder.encode(page, Charsets.UTF_8)
            val res = getJson("https://$lang.wikipedia.org/w/api.php?action=query&format=json&prop=pageprops&ppprop=page_image_free&redirects=1&titles=$t") ?: continue
            val file = ArtistImageParser.pageImageFree(res) ?: continue
            if (!seen.add(file)) continue
            // 파일명만으로 걸리면 Commons를 부르지 않는다
            val first = if (ArtistImageParser.rejectReason(file, null) == null) info(file, SHORT_SIDE) ?: continue else null
            val reason = ArtistImageParser.rejectReason(file, first)
            if (reason != null) {
                log.info("가수 사진 위키 대표 이미지 거름: mbid={} wiki={} reason={} file={}", mbid, wiki, reason, file)
                continue
            }
            val photo = photoFrom(file, first!!) ?: continue
            log.info("가수 사진 위키 대표 이미지: mbid={} wiki={} file={}", mbid, wiki, file)
            return photo.copy(via = wiki)
        }
        return null
    }

    private fun info(file: String, width: Int): JsonNode? {
        val title = URLEncoder.encode("File:$file", Charsets.UTF_8)
        return getJson("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=$width&titles=$title")
    }

    private fun photoOf(file: String): ArtistPhoto? = info(file, SHORT_SIDE)?.let { photoFrom(file, it) }

    private fun photoFrom(file: String, first: JsonNode): ArtistPhoto? {
        val (w, h) = ArtistImageParser.size(first) ?: return ArtistImageParser.photo(first)
        val width = thumbWidthOf(w, h)
        return ArtistImageParser.photo(if (width == SHORT_SIDE) first else info(file, width) ?: first)
    }

    /** Commons 이미지 파일만 내려받는다 (worker가 S3에 저장) */
    fun file(url: String): CoverImage? {
        require(URI(url).host in IMAGE_HOSTS) { "Wikimedia 이미지 주소가 아닙니다" }
        val res = send(url, HttpResponse.BodyHandlers.ofByteArray())
        val type = res.headers().firstValue("Content-Type").orElse("")
        return when {
            res.statusCode() in 200..299 && type.startsWith("image/") -> CoverImage(res.body(), type)
            res.statusCode() == 404 -> null
            else -> throw UpstreamException("가수 사진 응답 ${res.statusCode()}")
        }
    }

    companion object {
        const val SHORT_SIDE = 250
        const val MAX_WIDTH = 1000
        const val MAX_TITLES = 3
        const val MAX_CANDIDATES = 5

        /** 썸네일 너비: 짧은 쪽이 [SHORT_SIDE]px가 되게 (가로로 긴 단체 사진이 흐려지지 않게), 너무 큰 파일은 막는다 */
        fun thumbWidthOf(width: Int, height: Int): Int =
            if (width <= 0 || height <= 0 || width <= height) SHORT_SIDE
            else minOf((SHORT_SIDE.toLong() * width / height).toInt(), MAX_WIDTH)

        /** Commons 썸네일은 thumb.wikimedia.org, 원본은 upload.wikimedia.org */
        val IMAGE_HOSTS = setOf("upload.wikimedia.org", "thumb.wikimedia.org")
    }

    private fun getJson(url: String): JsonNode? {
        val res = send(url, HttpResponse.BodyHandlers.ofString())
        return when {
            res.statusCode() in 200..299 -> json.readTree(res.body())
            res.statusCode() == 404 -> null
            else -> throw UpstreamException("Wikimedia 응답 ${res.statusCode()}")
        }
    }

    private fun <T> send(url: String, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        gate.acquire()
        return try {
            http.send(HttpRequest.newBuilder(URI(url)).GET().header("User-Agent", props.userAgent).timeout(Duration.ofSeconds(60)).build(), handler)
        } catch (e: Exception) {
            throw UpstreamException("Wikimedia에 연결하지 못했습니다", e)
        }
    }
}

/** worker가 부른다. 사진이 없으면 404 */
@RestController
class ArtistImageController(private val client: ArtistImageClient) {
    private val mbidPattern = Regex("""^[0-9a-f-]{36}$""")

    @GetMapping("/internal/artist-image/commons")
    fun commons(@RequestParam mbid: String): ResponseEntity<ArtistPhoto> {
        require(mbidPattern.matches(mbid)) { "mbid 형식이 올바르지 않습니다" }
        return client.commons(mbid)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    @GetMapping("/internal/artist-image/find-artist")
    fun findArtist(@RequestParam name: String, @RequestParam(required = false) title: List<String>?): ResponseEntity<Map<String, String>> {
        require(name.isNotBlank() && name.length <= 200) { "이름이 올바르지 않습니다" }
        return client.findArtist(name, title.orEmpty().filter { it.length <= 300 })?.let { ResponseEntity.ok(mapOf("mbid" to it)) } ?: ResponseEntity.notFound().build()
    }

    @GetMapping("/internal/artist-image/file")
    fun file(@RequestParam url: String): ResponseEntity<ByteArray> {
        val img = client.file(url) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(img.contentType)).body(img.bytes)
    }
}
