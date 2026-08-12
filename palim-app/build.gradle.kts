plugins {
    java
    // 실행 가능한 부트 jar 는 이 모듈에서만 생성한다.
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":palim-collector"))
    implementation(project(":palim-monitor"))
    implementation(project(":palim-web"))

    // @EnableJpaAuditing 을 이 모듈이 선언하므로 컴파일 시점에 필요하다.
    // 하위 모듈은 implementation 으로 연결되어 컴파일 classpath 로 전이되지 않는다.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Spring Boot 4 는 autoconfigure 를 기술별 모듈로 분리했다. Flyway 는 starter 가 없어서
    // flyway-core 만 넣으면 자동 구성이 붙지 않고 마이그레이션이 조용히 실행되지 않는다.
    // (Boot 3 에서는 flyway-core 존재만으로 감지됐다.)
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(testFixtures(project(":palim-common")))

    // 스키마·제약 검증은 전체 컨텍스트에서 해야 의미가 있다(ddl-auto=validate 가 모든 엔티티를 본다).
    // 도메인 모듈은 implementation 으로 연결되어 컴파일 classpath 에 없으므로 테스트에만 추가한다.
    // 도메인 규칙 자체는 각 모듈에서 Spring 없는 단위 테스트로 검증한다(설계서 8장).
    testImplementation(project(":palim-audit"))
    testImplementation(project(":palim-automation"))
    testImplementation(project(":palim-auth"))
    testImplementation(project(":palim-channel"))
    testImplementation(project(":palim-connector"))
    testImplementation(project(":palim-incident"))
    testImplementation(project(":palim-mapping"))
    testImplementation(project(":palim-notification"))
    testImplementation(project(":palim-order"))
    testImplementation(project(":palim-sku"))
    testImplementation(project(":palim-monitor"))
}
