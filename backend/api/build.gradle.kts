// REST API, 서명 URL 발급, 통계 조회. DB 마이그레이션(Flyway)은 이 앱이 시작할 때만 실행한다.
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":backend:core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-localstack")
}
