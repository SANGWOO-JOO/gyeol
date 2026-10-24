package dev.gyeol.fetcher

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FetcherApplication

fun main(args: Array<String>) {
    runApplication<FetcherApplication>(*args)
}
