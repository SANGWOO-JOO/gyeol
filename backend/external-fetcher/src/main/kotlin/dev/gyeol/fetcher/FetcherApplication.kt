package dev.gyeol.fetcher

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration

@SpringBootApplication
class FetcherApplication

@Configuration
@EnableConfigurationProperties(FetcherProperties::class)
class FetcherConfig(props: FetcherProperties) {
    init {
        // MusicBrainz는 연락처가 담긴 User-Agent를 요구한다. 없으면 차단될 수 있으므로 시작하지 않는다.
        require(props.contact.isNotBlank()) { "GYEOL_CONTACT 환경 변수(연락처)가 필요합니다. 예: https://github.com/SANGWOO-JOO" }
    }
}

fun main(args: Array<String>) {
    runApplication<FetcherApplication>(*args)
}
