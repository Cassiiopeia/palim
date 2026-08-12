plugins {
    `java-library`
}

dependencies {
    // palim-common 이 spring-boot-starter-data-jpa 와 starter-json 을 api 로 노출한다.
    api(project(":palim-common"))

    // 라이징 주간 알림. 텔레그램 봇 API 를 직접 부르지 않고 Outbox 를 거친다 —
    // 발송 실패가 재시도·이력 관리 체계 안에 남아야 한다(05-INTEGRATION).
    implementation(project(":palim-notification"))

    // YouTube Data API 호출(RestClient). web starter 는 쓰지 않는다 — 이 모듈은 서버가 아니라
    // 클라이언트만 필요하고, 화면 계층의 자동 구성이 수집 동작에 영향을 주면 안 된다.
    implementation("org.springframework:spring-web")

    testImplementation(testFixtures(project(":palim-common")))
}
