plugins {
    `java-library`
}

dependencies {
    // 수집 조율 — 여러 도메인을 관통하는 트랜잭션을 여는 유일한 계층 (설계서 규칙 2)
    implementation(project(":palim-channel"))
    implementation(project(":palim-mapping"))
    implementation(project(":palim-order"))
    implementation(project(":palim-sku"))
    implementation(project(":palim-notification"))
    // 오버셀·미매핑·수집 중단을 인시던트로 보고한다 (#34)
    implementation(project(":palim-incident"))

    testImplementation(testFixtures(project(":palim-common")))
}
