plugins {
    `java-library`
}

dependencies {
    api(project(":palim-common"))

    // Outbox relay 가 발행하는 큐 (설계서 7장)
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    // 텔레그램 Bot API 호출
    implementation("org.springframework:spring-web")

    testImplementation(testFixtures(project(":palim-common")))
}
