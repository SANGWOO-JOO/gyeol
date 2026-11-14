package dev.gyeol.api.search

import dev.gyeol.core.identify.AliasKey
import dev.gyeol.core.identify.ArtistNames
import dev.gyeol.core.identify.DbTrackHit
import dev.gyeol.core.identify.SearchText
import dev.gyeol.core.identify.TrackSearchRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 가수 묶기·같은 곡 합치기 (I-30 ①, docs/todo/artist-search.md 2절). LLM 없이 규칙만:
 * - 가수: Last.fm 가수 이름(정규화)이 우리 가수 한 명의 이름·별칭과 같으면 그 가수로 바꿔 쓴다(동명이인이면 그대로)
 * - 곡: ① 녹음 MBID ② 식별 캐시(title_alias "제목|가수 - Topic") ③ 같은 가수이고 제목 표기가 겹침(괄호 양쪽 "A (B)", 한글·로마자 두 언어 "NAN CHUN 난춘")
 * 근거가 없으면 합치지 않는다 (틀리게 합치는 것보다 두 줄이 낫다). 같은 곡이면 우리 DB 곡을 남긴다
 */
@Component
class TrackMerger(private val db: TrackSearchRepository) {

    companion object {
        private val BRACKET = Regex("""^(.+?)\s*[(（\[]([^)）\]]+)[)）\]]\s*$""")
        private val HANGUL = Regex("""[가-힣ㄱ-ㅎ]""")
        private val LATIN = Regex("""[A-Za-z]""")
        /** 괄호 안이 판 표시면 다른 녹음이다: 양쪽 표기로 나누지 않는다 ("밤편지 (Inst.)"는 "밤편지"와 다른 곡) */
        val VERSION = dev.gyeol.core.identify.TitleVersion.REGEX

        /** 같은 곡 판정용 제목 표기들: 전체, 괄호 앞·안, 한글·로마자로 나뉜 두 쪽 (모두 정규화) */
        fun titleForms(title: String): Set<String> {
            val out = linkedSetOf(SearchText.norm(title))
            BRACKET.find(title.trim())?.takeIf { !VERSION.containsMatchIn(it.groupValues[2]) }?.let { m ->
                out += SearchText.norm(m.groupValues[1]); out += SearchText.norm(m.groupValues[2])
            }
            // 괄호가 있거나 판 표시가 있으면 나누지 않는다 ("밤편지 (Inst.)"·"좋은 날 Live")
            if (BRACKET.find(title.trim()) == null && !VERSION.containsMatchIn(title)) {
                scriptHalves(title)?.let { (a, b) -> out += SearchText.norm(a); out += SearchText.norm(b) }
            }
            return out.filter { it.isNotEmpty() }.toSet()
        }

        /** "NAN CHUN 난춘"·"난춘 NAN CHUN": 단어가 한 글자 체계로만 되어 있고 한글·로마자가 한 번만 바뀔 때 양쪽 */
        fun scriptHalves(s: String): Pair<String, String>? {
            val words = s.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
            // 기호만 있는 단어("&", "+")는 어느 쪽도 아니다 ("너랑 나 You & I"). 숫자가 든 단어("2", "Pt.2")나 한글·로마자가 섞인 단어가 있으면
            // 나누지 않는다 ("더보기 DB 0"·"편지 Pt. 2"를 앞부분으로 합치지 않게)
            val kinds = words.map { w ->
                val h = HANGUL.containsMatchIn(w); val l = LATIN.containsMatchIn(w)
                when { h && l -> '?'; h -> 'H'; l -> 'L'; w.any(Char::isLetterOrDigit) -> '?'; else -> null }
            }
            if ('?' in kinds) return null
            val lettered = kinds.filterNotNull()
            if (lettered.toSet().size != 2 || lettered.zipWithNext().count { (a, b) -> a != b } != 1) return null
            val cut = kinds.indexOfFirst { it != null && it != lettered.first() }
            return words.take(cut).joinToString(" ") to words.drop(cut).joinToString(" ")
        }

        /** 'H' 한글만, 'L' 로마자만, 섞이거나 둘 다 없으면 null */
        private fun script(s: String): Char? {
            val h = HANGUL.containsMatchIn(s); val l = LATIN.containsMatchIn(s)
            return when { h && !l -> 'H'; l && !h -> 'L'; else -> null }
        }

        /** 가수 이름에 붙는 채널 군더더기 ("TAEYEON Official", "가수 - Topic") */
        private val ARTIST_NOISE_WORD = Regex("""(?i)(\s*-\s*)?\b(official|topic|vevo)\b""")

        fun cleanArtist(name: String): String = name.replace(ARTIST_NOISE_WORD, " ").trim().replace(Regex("""\s+"""), " ")

        /**
         * Last.fm 가수 이름의 조각 (I-54): 군더더기를 뺀 이름, 쉼표로 나뉜 이름들, 한글·로마자 병기의 양쪽("윤하 Younha"),
         * 괄호 병기의 앞쪽과, 글자 체계가 다를 때만 괄호 안쪽("다비치 (Davichi)"). "김태연 (소녀시대)"처럼 같은 글자 체계의 괄호는 소속일 수 있어 쓰지 않는다
         */
        fun artistParts(name: String): List<String> {
            val whole = name.trim()
            val cleaned = cleanArtist(whole)
            val out = linkedSetOf(cleaned)
            for (piece in cleaned.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }) {
                out += piece
                BRACKET.find(piece)?.let { m ->
                    val outside = m.groupValues[1].trim(); val inside = m.groupValues[2].trim()
                    out += outside
                    val so = script(outside); val si = script(inside)
                    if (so != null && si != null && so != si) out += inside
                }
                scriptHalves(piece)?.let { (a, b) -> out += a; out += b }
            }
            return out.filter { it != whole && SearchText.norm(it).length >= 2 }
        }

        // 트랙 번호: "01 곡"·"15 곡"(두 자리) 또는 "1. 곡". "7 Rings"처럼 숫자로 시작하는 제목은 건드리지 않는다
        private val TRACK_NO = Regex("""^\s*(?:\d{2}\s+|\d{1,3}[.)]\s*)(?=\S)""")
        private val FEAT = Regex("""(?i)\s*[(\[（]\s*(?:feat|ft|featuring)\.?\s[^)\]）]*[)\]）]|\s+(?:feat|ft|featuring)\.?\s.*$""")
        private val ORIGINAL = Regex("""(?i)\s*[(\[（]?\s*original\s+ver(?:sion|\.)?\s*[)\]）]?\s*$""")

        /**
         * 같은 가수의 곡끼리 비교할 때 쓰는 넓은 표기 (I-54): [titleForms]에 더해 "곡 - 가수" 꼬리, 앞 트랙 번호, feat. 부분,
         * "Original Ver(sion)"을 뗀 제목의 표기. Inst·Remix·Live 등 판 표시는 그대로 남아 다른 녹음으로 본다
         */
        fun looseForms(title: String, artists: Collection<String>): Set<String> {
            var t = title.trim()
            t.replace(TRACK_NO, "").takeIf { it.isNotBlank() }?.let { t = it }
            for (a in artists.map { it.trim() }.filter { it.isNotEmpty() }.flatMap { listOf(it, cleanArtist(it)) }.distinct()) {
                t = t.replace(Regex("""\s+[-–]\s+${Regex.escape(a)}\s*$""", RegexOption.IGNORE_CASE), "")
            }
            t = t.replace(FEAT, "").replace(ORIGINAL, "").trim()
            return if (t.isEmpty() || t == title.trim()) titleForms(title) else titleForms(title) + titleForms(t)
        }

        fun item(h: DbTrackHit) = TrackItem(h.title, h.artist, h.recordingMbid.toString(), null, h.releaseYear, h.trackId, h.coverUrl, h.artistId)
    }

    /** 이름(정규화) → 우리 가수 (한 명만 맞을 때) */
    fun resolveArtists(names: Collection<String>): Map<String, ArtistNames> {
        val byNorm = names.filter { SearchText.norm(it).isNotEmpty() }.associateBy(SearchText::norm)
        if (byNorm.isEmpty()) return emptyMap()
        val parts = byNorm.mapValues { (_, name) -> artistParts(name) }
        val lookup = (byNorm.values + parts.values.flatten()).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val candidates = db.artistsByNames(lookup)
        fun hits(n: String) = candidates.filter { a -> SearchText.norm(a.name) == n || a.aliases.any { SearchText.norm(it) == n } }
        return byNorm.keys.mapNotNull { n ->
            val hit = hits(n)
            // 이름 전체가 안 맞으면 조각으로 (I-54): 조각들이 가리키는 가수가 모두 합쳐 한 명일 때만 묶는다 (둘 이상이면 묶지 않음)
            if (hit.isEmpty()) {
                val byParts = parts[n].orEmpty().flatMap { hits(SearchText.norm(it)) }.distinctBy { it.id }
                return@mapNotNull byParts.singleOrNull()?.let { n to it }
            }
            // 여럿이면: 대표 이름이 같은 가수 하나 → 우리 DB에 들린 곡이 있는 가수 하나 (예: 별칭 "Sung Si Kyung"은 성시경과 "Sung Si Kyung with friends"가 함께 가짐)
            val pick = hit.singleOrNull()
                ?: hit.filter { SearchText.norm(it.name) == n }.singleOrNull()
                ?: hit.filter { it.plays > 0 }.singleOrNull()
            pick?.let { n to it }
        }.toMap()
    }

    /**
     * DB 결과(확실한·약한)와 Last.fm 결과를 합친다. 순서: 확실한 DB → Last.fm(우리 곡으로 바뀐 것 포함) → 약한 DB, 같은 곡은 처음 것만
     */
    fun merge(strong: List<DbTrackHit>, weak: List<DbTrackHit>, remote: List<TrackItem>, limit: Int): List<TrackItem> {
        val local = strong + weak
        val resolved = resolveArtists(remote.map { it.artist })
        // 우리 가수의 표시 이름: DB 결과에 그 가수가 있으면 그 표기(최빈 YouTube 표기), 없으면 MusicBrainz 이름
        val shown = local.groupBy { it.artistId }.mapValues { (_, hs) -> hs.groupingBy { it.artist }.eachCount().maxBy { it.value }.key }
        val named = remote.map { r ->
            val a = resolved[SearchText.norm(r.artist)]
            if (a == null) r to r.artist else r.copy(artist = shown[a.id] ?: a.name, artistId = a.id) to r.artist
        }

        // Last.fm 곡 → 우리 곡: MBID, 식별 캐시, 같은 가수의 제목 표기
        val byMbid = db.trackIdsByMbids(named.mapNotNull { (r, _) -> r.mbid?.let { runCatching { UUID.fromString(it) }.getOrNull() } })
        val keys = named.associate { (r, orig) -> r to listOf(AliasKey.of(r.title, "$orig - Topic"), AliasKey.of(r.title, "${r.artist} - Topic")).distinct() }
        val byAlias = db.aliasTracks(keys.values.flatten().toSet())
        // 같은 가수의 곡은 검색 결과에 없어도 비교한다 ("다비치" 검색에 성시경 곡이 Last.fm으로 섞인 경우 등). 가수 수가 적어 한 번씩 읽는다
        val otherArtists = named.mapNotNull { it.first.artistId }.toSet() - local.map { it.artistId }.toSet()
        val artistTracks = otherArtists.flatMap { db.hitsOfArtist(it) }
        // 같은 가수끼리만 비교하므로 넓은 표기(꼬리 가수·트랙 번호·feat. 제거)를 쓴다 (I-54)
        val localForms = (local + artistTracks).map { h -> h to (looseForms(h.title, listOf(h.artist)) + looseForms(h.mbTitle, listOf(h.artist))) }
        val mapped = named.map { (r, orig) ->
            val id = r.mbid?.let { m -> runCatching { UUID.fromString(m) }.getOrNull()?.let(byMbid::get) }
                ?: keys[r]!!.firstNotNullOfOrNull(byAlias::get)
                ?: r.artistId?.let { aid ->
                    val f = looseForms(r.title, listOf(r.artist, orig))
                    localForms.firstOrNull { (h, hf) -> h.artistId == aid && hf.any(f::contains) }?.first?.trackId
                }
            r to id
        }
        val extra = db.hits(mapped.mapNotNull { it.second }.toSet() - (local + artistTracks).map { it.trackId }.toSet()).associateBy { it.trackId }
        val localById = (local + artistTracks).associateBy { it.trackId }
        val remoteItems = mapped.map { (r, id) -> id?.let { (localById[it] ?: extra[it])?.let(::item) } ?: r }

        return unifyArtistNames(dedupe(strong.map(::item) + remoteItems + weak.map(::item)).take(limit))
    }

    /** 같은 가수(우리 id)는 결과 안에서 한 표기로 (가장 많이 나온 표기, 같으면 먼저 나온 것). "다비치"와 "DAVICHI"가 섞여 보이지 않게 */
    fun unifyArtistNames(items: List<TrackItem>): List<TrackItem> {
        val shown = items.filter { it.artistId != null }.groupBy { it.artistId }
            .mapValues { (_, xs) -> xs.groupingBy { it.artist }.eachCount().let { c -> xs.map { it.artist }.distinct().maxBy { c[it]!! } } }
        return items.map { t -> t.artistId?.let { shown[it] }?.let { t.copy(artist = it) } ?: t }
    }

    /** 같은 곡 id, 또는 같은 가수(우리 id 또는 정규화 이름)이고 제목 표기가 겹치면 처음 것만 */
    fun dedupe(items: List<TrackItem>): List<TrackItem> {
        val seenIds = mutableSetOf<Long>()
        val seen = mutableListOf<Pair<String, Set<String>>>()
        return items.filter { t ->
            if (t.trackId != null && !seenIds.add(t.trackId)) return@filter false
            val key = t.artistId?.let { "id:$it" } ?: "n:${SearchText.norm(cleanArtist(t.artist))}"
            val forms = looseForms(t.title, listOf(t.artist))
            if (seen.any { (k, f) -> k == key && f.any(forms::contains) }) return@filter false
            seen += key to forms
            true
        }
    }
}
