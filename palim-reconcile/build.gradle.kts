plugins {
    `java-library`
}

dependencies {
    // palim-common 이 spring-boot-starter-data-jpa 를 api 로 노출한다.
    api(project(":palim-common"))

    testImplementation(testFixtures(project(":palim-common")))
}
