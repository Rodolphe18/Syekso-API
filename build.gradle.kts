// The whole server: Spring Boot 4 on Java 21, one Gradle project, no subprojects.
//
// It was a `:spring` subproject for as long as the Ktor server it replaces lived beside it in src/.
// Both are gone — Ktor deleted once the rewrite reached parity, the subproject flattened straight
// after — so the indirection had nothing left to keep apart.

plugins {
    java

    // Adds bootRun / bootJar, and drives the version management below.
    id("org.springframework.boot") version "4.0.8"

    // Applies Spring Boot's bill of materials: the starters below carry no version number, and Boot
    // picks a set of versions known to work together.
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.rodolphe.accesscontrol"
version = "0.1.0"

java {
    toolchain {
        // Gradle downloads this JDK if it is not installed — see the resolver in settings.gradle.kts.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring MVC + embedded Tomcat + Jackson. This one line replaced the four Ktor artifacts the
    // deleted server needed (core, netty, content-negotiation, serialization).
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Brings the Mongo driver, the document mapper, and the repository machinery that turns the
    // repository interfaces — one per feature package — into working beans. It also auto-configures the connection from the
    // spring.data.mongodb.* properties — which is why connectMongo() has no counterpart here.
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")

    // Jakarta Bean Validation. Note it only works paired with an explicit handler for
    // MethodArgumentNotValidException in ApiExceptionHandler — without one, the broad catch-all turns
    // every rejected field into a 500 instead of a 400.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Raw WebSocket, not STOMP: the protocol is a custom JSON envelope two Android apps already
    // speak, so there is nothing to gain from a messaging layer on top.
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Pinned rather than left to Boot's management: this library signs the tokens two deployed
    // Android apps carry, so a silent bump is a wire-format change waiting to happen.
    implementation("com.auth0:java-jwt:4.5.2")

    // Supersedes the standalone spring-security-crypto of iteration 2: the starter brings BCrypt and
    // the filter chain. Note it secures every endpoint by default — SecurityConfig is what decides
    // which ones are public again.
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Boot 4 split the test slices per technology: spring-boot-test-autoconfigure no longer carries
    // MockMvc, so @AutoConfigureMockMvc/@WebMvcTest need this starter. Every 3.x tutorial gets it for
    // free from spring-boot-starter-test, which is why the import failed to resolve.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
