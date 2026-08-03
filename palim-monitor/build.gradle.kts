plugins {
    `java-library`
}

dependencies {
    // 내부 상태를 점검해 이상을 알리는 조율 계층이다.
    // palim-collector 와 방향이 반대다 — collector 는 외부→내부, monitor 는 내부→알림.
    implementation(project(":palim-channel"))
    implementation(project(":palim-incident"))
    implementation(project(":palim-mapping"))
    implementation(project(":palim-notification"))
    implementation(project(":palim-order"))
    implementation(project(":palim-sku"))

    // 일일 리포트는 여러 도메인에 걸친 집계라 JPA 로 조인할 수 없다.
    // JdbcClient 로 직접 SQL 을 쓴다 (02-ARCHITECTURE 규칙 3).
    implementation("org.springframework:spring-jdbc")

    testImplementation(testFixtures(project(":palim-common")))
}
