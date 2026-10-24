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
) {
    data class MusicBrainz(
        val baseUrl: String = "https://musicbrainz.org/ws/2",
        val minInterval: Duration = Duration.ofSeconds(1),
        val maxWait: Duration = Duration.ofMillis(2500),
        val timeout: Duration = Duration.ofSeconds(2),
    )

    data class LastFm(
        val apiKey: String = "",
        val baseUrl: String = "https://ws.audioscrobbler.com/2.0/",
        val minInterval: Duration = Duration.ofMillis(250),
        val maxWait: Duration = Duration.ofSeconds(2),
        val timeout: Duration = Duration.ofSeconds(3),
    )

    data class Cache(
        val ttl: Duration = Duration.ofMinutes(10),
        val maxEntries: Int = 500,
    )

    /** 예: gyeol/0.1.0 ( https://github.com/SANGWOO-JOO ) */
    val userAgent: String get() = "gyeol/$appVersion ( $contact )"
}
