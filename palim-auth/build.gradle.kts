plugins {
    `java-library`
}

dependencies {
    api(project(":palim-common"))

    // 비밀번호 해싱만 필요하다. security starter 전체는 palim-web 이 갖는다.
    implementation("org.springframework.security:spring-security-crypto")

    testImplementation(testFixtures(project(":palim-common")))
}
