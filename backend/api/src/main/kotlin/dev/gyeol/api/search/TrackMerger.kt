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
        val VERSION = Regex("""(?i)\b(inst|instrumental|mr|live|remix|mix|ver|version|edit|remaster(ed)?|acoustic|demo|feat|ft|prod|sped|slowed|cover|karaoke)\b|라이브|리믹스|버전|반주|어쿠스틱""")

        /** 같은 곡 판정용 제목 표기들: 전체, 괄호 앞·안, 한글·로마자로 나뉜 두 쪽 (모두 정규화) */
        fun titleForms(title: String): Set<String> {
            val out = linkedSetOf(SearchText.norm(title))
            BRACKET.find(title.trim())?.takeIf { !VERSION.containsMatchIn(it.groupValues[2]) }?.let { m ->
                out += SearchText.norm(m.groupValues[1]); out += SearchText.norm(m.groupValues[2])
            }
            // "NAN CHUN 난춘"·"난춘 NAN CHUN": 단어가 한 글자 체계로만 되어 있고 체계가 한 번만 바뀔 때 양쪽
            val words = title.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
            val kinds = words.map { w ->
                val h = HANGUL.containsMatchIn(w); val l = LATIN.containsMatchIn(w)
                when { h && !l -> 'H'; l && !h -> 'L'; else -> '?' }
            }
            // 괄호가 있거나 판 표시가 있으면 나누지 않는다 ("밤편지 (Inst.)"·"좋은 날 Live")
            if ('?' !in kinds && kinds.toSet().size == 2 && BRACKET.find(title.trim()) == null && !VERSION.containsMatchIn(title)) {
                val switches = kinds.zipWithNext().count { (a, b) -> a != b }
                if (switches == 1) {
                    val cut = kinds.indexOfFirst { it != kinds.first() }
                    out += SearchText.norm(words.take(cut).joinToString(" "))
                    out += SearchText.norm(words.drop(cut).joinToString(" "))
                }
            }
            return out.filter { it.isNotEmpty() }.toSet()
        }

        fun item(h: DbTrackHit) = TrackItem(h.title, h.artist, h.recordingMbid.toString(), null, h.releaseYear, h.trackId, h.coverUrl, h.artistId)
    }

    /** 이름(정규화) → 우리 가수 (한 명만 맞을 때) */
    fun resolveArtists(names: Collection<String>): Map<String, ArtistNames> {
        val norms = names.map(SearchText::norm).filter { it.isNotEmpty() }.toSet()
        if (norms.isEmpty()) return emptyMap()
        val candidates = db.artistsByNames(names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())
        return norms.mapNotNull { n ->
            val hit = candidates.filter { a -> SearchText.norm(a.name) == n || a.aliases.any { SearchText.norm(it) == n } }
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
        val localForms = (local + artistTracks).map { h -> h to (titleForms(h.title) + titleForms(h.mbTitle)) }
        val mapped = named.map { (r, _) ->
            val id = r.mbid?.let { m -> runCatching { UUID.fromString(m) }.getOrNull()?.let(byMbid::get) }
                ?: keys[r]!!.firstNotNullOfOrNull(byAlias::get)
                ?: r.artistId?.let { aid ->
                    val f = titleForms(r.title)
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
            val key = t.artistId?.let { "id:$it" } ?: "n:${SearchText.norm(t.artist)}"
            val forms = titleForms(t.title)
            if (seen.any { (k, f) -> k == key && f.any(forms::contains) }) return@filter false
            seen += key to forms
            true
        }
    }
}
