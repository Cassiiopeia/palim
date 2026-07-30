plugins {
    `java-library`
    // 통합 테스트 베이스를 전 모듈에서 재사용하기 위한 소스셋
    `java-test-fixtures`
}

dependencies {
    // BaseTimeEntity 가 JPA 애너테이션을 사용하므로 하위 모듈에 전파한다.
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    // UUIDv7 (설계서 4.1)
    api("com.fasterxml.uuid:java-uuid-generator:5.2.0")

    // --- 통합 테스트 픽스처 ---
    // Spring Boot 4 의 dependency-management 는 Testcontainers 버전을 관리하지 않으므로
    // BOM 을 직접 지정한다. 2.x 는 모듈 좌표가 개편되어 1.21.x 계열로 고정한다.
    testFixturesApi(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.testcontainers:junit-jupiter")
    testFixturesApi("org.testcontainers:postgresql")
}
