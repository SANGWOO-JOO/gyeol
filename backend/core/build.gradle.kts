// 도메인 모델, 공통 설정, DB 접근, DB 마이그레이션(SQL 파일). 실행 앱이 아닌 라이브러리.
plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("tools.jackson.module:jackson-module-kotlin")
}
