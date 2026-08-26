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

    // 메일 발송.
    //
    // spring-context-support 만 넣으면 타입은 컴파일되지만 실제 발송 시점에 구현이 없어
    // 죽는다. starter 를 써야 한다. 버전은 BOM 이 관리하므로 적지 않는다.
    //
    // 자동 구성이 만드는 발송기 빈은 «쓰지 않는다» — 서버 정보가 설정 파일이 아니라 DB 에
    // 있고, 그 빈을 주입받는 순간 설정이 없는 환경(테스트 전부)에서 앱이 아예 뜨지 않는다.
    implementation("org.springframework.boot:spring-boot-starter-mail")

    testImplementation(testFixtures(project(":palim-common")))
}
