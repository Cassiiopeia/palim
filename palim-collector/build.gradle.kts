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

    testImplementation(testFixtures(project(":palim-common")))
}
