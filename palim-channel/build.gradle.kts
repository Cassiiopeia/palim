plugins {
    `java-library`
}

dependencies {
    api(project(":palim-common"))

    // 채널 API 호출 — RestClient 를 쓴다. 톰캣이 필요없으므로 web starter 를 넣지 않는다.
    implementation("org.springframework:spring-web")

    // 채널별 호출 제한 준수 (설계서 6.1)
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")

    // 인증정보 AES-GCM 암호화 (설계서 6.2)
    implementation("org.springframework.security:spring-security-crypto")

    testImplementation(testFixtures(project(":palim-common")))
}
