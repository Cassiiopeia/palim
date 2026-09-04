plugins {
    java
    // 루트는 실행 가능한 jar를 만들지 않는다. bootJar는 palim-app 에서만 생성한다.
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    // 화면 스타일(Tailwind CSS 4 + daisyUI 5) 빌드용. palim-web 에서만 적용한다.
    id("com.github.node-gradle.node") version "7.1.0" apply false
    // SBOM 산출 (09-SECURITY). 루트에 적용하면 전 서브모듈 의존성이 하나의 BOM 으로 집계된다.
    // `./gradlew cyclonedxBom` → build/reports/bom.json
    id("org.cyclonedx.bom") version "2.4.1"
}

allprojects {
    group = "kr.suhsaechan"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // 파라미터 이름을 클래스 파일에 남긴다. 없으면 Spring Data 의 명명 파라미터 @Query 가
    // "provide names for method parameters" 로 실패한다 — 부트 플러그인이 붙는 모듈에만
    // 자동 적용되므로 java-library 모듈까지 덮으려면 여기서 지정해야 한다.
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        // 로컬에서 Gradle 배포판 다운로드가 막히는 환경이라 CI 로그가 유일한 진단 창구다.
        // 축약된 스택트레이스로는 스키마 검증 실패 같은 원인을 알 수 없어 전문을 남긴다.
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            events("failed")
            showStackTraces = true
            showCauses = true
        }
    }
}
