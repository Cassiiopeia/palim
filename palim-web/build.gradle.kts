import com.github.gradle.node.npm.task.NpmTask

plugins {
    `java-library`
    // 화면 스타일 빌드. Node 는 플러그인이 직접 내려받으므로 CI 에 Node 설치 단계가 필요 없다.
    id("com.github.node-gradle.node")
}

dependencies {
    // 감사 로그 기록·조회. 인증 사건과 화면 조회·변경을 남긴다 (07-DECISIONS 018).
    implementation(project(":palim-audit"))
    implementation(project(":palim-auth"))
    implementation(project(":palim-channel"))
    // 매핑 등록 직후 재고 소급 반영을 호출한다 (F-04).
    implementation(project(":palim-collector"))
    // 인시던트 화면 (#34)
    implementation(project(":palim-incident"))
    implementation(project(":palim-mapping"))
    implementation(project(":palim-notification"))
    implementation(project(":palim-order"))
    implementation(project(":palim-sku"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 여러 도메인에 걸친 화면용 조회는 JPA 로 조인할 수 없어 직접 SQL 을 쓴다
    // (02-ARCHITECTURE 규칙 3). 주의 — JdbcClient 에는 Instant 를 바인딩할 수 없다.
    implementation("org.springframework:spring-jdbc")

    testImplementation(testFixtures(project(":palim-common")))
    testImplementation("org.springframework.security:spring-security-test")
}

/**
 * Tailwind CSS 4 + daisyUI 5 빌드.
 *
 * 인터넷이 있는 환경을 전제로 한다. 사내 npm 미러 설정을 커밋하지 않으며, 로컬에서 막히면
 * CI 가 검증한다 — Gradle 배포판 다운로드와 같은 구조다(06-OPERATIONS).
 */
node {
    download = true
    version = "22.14.0"
    // 프로젝트 안에 설치해 개발자 로컬 Node 버전에 영향받지 않게 한다.
    workDir = layout.buildDirectory.dir("nodejs")
    nodeProjectDir = layout.projectDirectory
}

val buildCss = tasks.register<NpmTask>("buildCss") {
    group = "build"
    description = "Tailwind CSS 와 daisyUI 로 화면 스타일을 빌드한다"

    dependsOn(tasks.npmInstall)
    args = listOf("run", "build:css")

    // 템플릿이 바뀌면 사용된 클래스가 달라지므로 CSS 를 다시 만들어야 한다.
    inputs.file("package.json")
    inputs.dir("src/main/css")
    inputs.dir("src/main/resources/templates")
    outputs.dir(layout.projectDirectory.dir("src/main/resources/static/css"))
}

// CSS 를 jar 에 포함시킨다. 이 의존이 없으면 스타일 없는 화면이 배포된다.
tasks.named<ProcessResources>("processResources") {
    dependsOn(buildCss)
}
