plugins {
    `java-library`
}

dependencies {
    // palim-common 이 spring-boot-starter-data-jpa 를 api 로 노출한다.
    api(project(":palim-common"))

    // 알림은 도메인이 아니라 «내보내는 길» 이다. 조율 모듈(monitor·automation)이 쓰는 것과
    // 같은 방식으로 의존한다. 설계 문서는 common 만 의존한다고 적었지만, 임계 초과를 알리는
    // 일이 범위에 있는 이상 이 길이 필요하다.
    implementation(project(":palim-notification"))

    testImplementation(testFixtures(project(":palim-common")))
}
