// 외부 API 호출을 한 곳에 모은 서비스. 복제본 1개로 고정하고 API별로 속도를 제한한다 (L1은 MusicBrainz 검색만).
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
