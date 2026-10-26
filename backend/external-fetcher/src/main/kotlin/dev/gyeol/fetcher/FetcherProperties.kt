package dev.gyeol.fetcher

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("gyeol.fetcher")
data class FetcherProperties(
    val contact: String = "",
    val appVersion: String = "0.1.0",
    val musicbrainz: MusicBrainz = MusicBrainz(),
    val lastfm: LastFm = LastFm(),
    val cache: Cache = Cache(),
    val coverart: CoverArt = CoverArt(),
) {
    data class MusicBrainz(
        val baseUrl: String = "https://musicbrainz.org/ws/2",
        val minInterval: Duration = Duration.ofSeconds(1),
        val maxWait: Duration = Duration.ofMillis(2500),
        val timeout: Duration = Duration.ofSeconds(2),
        /** 백그라운드 곡 식별: 응답이 0.8~12초로 느려 제한 시간과 차례 대기를 길게 */
        val identifyTimeout: Duration = Duration.ofSeconds(20),
        val identifyMaxWait: Duration = Duration.ofSeconds(60),
    )

    data class LastFm(
        val apiKey: String = "",
        val baseUrl: String = "https://ws.audioscrobbler.com/2.0/",
        val minInterval: Duration = Duration.ofMillis(250),
        val maxWait: Duration = Duration.ofSeconds(2),
        val timeout: Duration = Duration.ofSeconds(3),
    )

    data class CoverArt(
        val baseUrl: String = "https://coverartarchive.org",
        // 공식 속도 제한은 확인하지 못함 (추정으로 여유 있게). 응답이 1.7~7초로 느리다 (docs/l1-design.md 4-3)
        val minInterval: Duration = Duration.ofMillis(500),
        val maxWait: Duration = Duration.ofSeconds(60),
        val timeout: Duration = Duration.ofSeconds(15),
    )

    data class Cache(
        val ttl: Duration = Duration.ofMinutes(10),
        val maxEntries: Int = 500,
    )

    /** 예: gyeol/0.1.0 ( https://github.com/SANGWOO-JOO ) */
    val userAgent: String get() = "gyeol/$appVersion ( $contact )"
}
