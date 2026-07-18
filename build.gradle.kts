import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

group = "dev.rodolphe.accesscontrol"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.1"
val mongoVersion = "5.9.0"
val logbackVersion = "1.5.12"

dependencies {
    // Ktor server: Netty engine + JSON content negotiation + JWT auth + status pages.
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Persistence: MongoDB via the official Kotlin coroutine driver, with the kotlinx.serialization
    // BSON codec so @Serializable data classes map straight to documents.
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:$mongoVersion")
    implementation("org.mongodb:bson-kotlinx:$mongoVersion")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Password hashing at rest — never store plaintext, even in a demo.
    implementation("org.mindrot:jbcrypt:0.4")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("dev.rodolphe.accesscontrol.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}
