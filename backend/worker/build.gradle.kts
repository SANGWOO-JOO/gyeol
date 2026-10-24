// 큐 워커. 어떤 큐를 처리할지는 환경 변수 WORKER_ROLE로 정한다 (L1은 parse만).
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":backend:core"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc") // 상태 확인(health) 엔드포인트용
    runtimeOnly("org.postgresql:postgresql")
}
