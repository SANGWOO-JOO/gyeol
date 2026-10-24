package dev.gyeol.worker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["dev.gyeol.worker", "dev.gyeol.core"])
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerApplication

fun main(args: Array<String>) {
    runApplication<WorkerApplication>(*args)
}
