plugins {
    `java-library`
}

dependencies {
    implementation(project(":palim-auth"))
    implementation(project(":palim-channel"))
    implementation(project(":palim-mapping"))
    implementation(project(":palim-notification"))
    implementation(project(":palim-order"))
    implementation(project(":palim-sku"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation(testFixtures(project(":palim-common")))
    testImplementation("org.springframework.security:spring-security-test")
}
