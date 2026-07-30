plugins {
    `java-library`
}

dependencies {
    api(project(":palim-common"))

    testImplementation(testFixtures(project(":palim-common")))
}
