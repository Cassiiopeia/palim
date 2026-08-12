plugins {
    `java-library`
}

dependencies {
    // palim-common 이 spring-boot-starter-data-jpa 와 starter-json 을 api 로 노출한다.
    api(project(":palim-common"))

    // HTTP 원천 어댑터(RestClient). web starter 는 쓰지 않는다 — 이 모듈은 서버가 아니라
    // 클라이언트만 필요하고, 화면 계층의 자동 구성이 수집 동작에 영향을 주면 안 된다.
    implementation("org.springframework:spring-web")

    testImplementation(testFixtures(project(":palim-common")))
}
