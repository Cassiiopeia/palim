plugins {
    `java-library`
}

dependencies {
    api(project(":palim-common"))

    // 채널 API 호출 — RestClient 를 쓴다. 톰캣이 필요없으므로 web starter 를 넣지 않는다.
    implementation("org.springframework:spring-web")

    // 채널 응답 JSON 파싱
    implementation("org.springframework.boot:spring-boot-starter-json")

    // 채널별 호출 제한 준수 (설계서 6.1)
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")

    // 인증정보 AES-GCM 암호화 (설계서 6.2)
    implementation("org.springframework.security:spring-security-crypto")

    testImplementation(testFixtures(project(":palim-common")))

    // 채널 API 를 흉내내 어댑터를 검증한다. 실제 인증정보 없이 페이징·인증실패·응답파싱을
    // 전부 확인할 수 있고, 실제 응답 샘플을 보관해두면 사양 변경도 감지된다(05-INTEGRATION).
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
}
