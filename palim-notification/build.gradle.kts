plugins {
    `java-library`
}

dependencies {
    api(project(":palim-common"))

    // RabbitMQ 는 현재 쓰지 않는다.
    //
    // 설계서 7장은 Outbox → relay → RabbitMQ → 워커 구조였으나, 현 규모에서 큐는 이득 없이
    // 이중 상태 관리를 만든다. 워커 분리는 단일 배포라 무의미하고, 재시도·DLQ 는 Outbox 가
    // attemptCount 로 이미 관리한다. 재시도가 두 곳에 생기면 어느 쪽이 진실인지 알 수 없다.
    // 필요해지면 그때 추가한다 — 미사용 의존성은 컨텍스트에 불필요한 자동 구성을 붙인다.

    // 텔레그램 Bot API 호출
    implementation("org.springframework:spring-web")

    // Outbox payload JSON 직렬화
    implementation("org.springframework.boot:spring-boot-starter-json")

    testImplementation(testFixtures(project(":palim-common")))
}
