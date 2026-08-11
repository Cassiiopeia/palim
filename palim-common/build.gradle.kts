plugins {
    `java-library`
    // 통합 테스트 베이스를 전 모듈에서 재사용하기 위한 소스셋
    `java-test-fixtures`
}

dependencies {
    // BaseTimeEntity 가 JPA 애너테이션을 사용하므로 하위 모듈에 전파한다.
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    // ErrorCode 가 HttpStatus 를 표현한다. 도메인이 자기 실패의 HTTP 의미를 아는 편이
    // 화면 계층에서 예외마다 상태 코드를 판단하는 것보다 낫다.
    api("org.springframework:spring-web")

    // UUIDv7 (설계서 4.1)
    api("com.fasterxml.uuid:java-uuid-generator:5.2.0")

    // SystemConfig 의 값은 jsonb 한 컬럼에 담기므로 읽는 쪽이 항상 JSON 을 파싱한다.
    // 설정을 쓰는 모든 모듈이 ConfigReader 를 통해 이 기능을 필요로 하므로 api 로 노출한다.
    api("org.springframework.boot:spring-boot-starter-json")

    // --- 통합 테스트 픽스처 ---
    // Spring Boot 4 의 dependency-management 는 Testcontainers 버전을 관리하지 않으므로
    // BOM 을 직접 지정한다. 2.x 는 모듈 좌표가 개편되어 1.21.x 계열로 고정한다.
    testFixturesApi(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.testcontainers:junit-jupiter")
    testFixturesApi("org.testcontainers:postgresql")
}
