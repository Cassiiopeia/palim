plugins {
    java
    // 실행 가능한 부트 jar 는 이 모듈에서만 생성한다.
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":palim-collector"))
    implementation(project(":palim-web"))

    // @EnableJpaAuditing 을 이 모듈이 선언하므로 컴파일 시점에 필요하다.
    // 하위 모듈은 implementation 으로 연결되어 컴파일 classpath 로 전이되지 않는다.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(testFixtures(project(":palim-common")))
}
