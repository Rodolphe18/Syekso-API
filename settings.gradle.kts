plugins {
    // Lets Gradle fetch the JDK a module asks for instead of requiring it on the machine. The Spring
    // module targets Java 21 while only 17 and 19 are installed here, and the build stays
    // reproducible on any other machine.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "AccessControllerServer"

// One project, no subprojects: the server is at the root. `:spring` existed only to let the rewrite
// live beside the Ktor server it replaced; Ktor was deleted at parity on 2026-08-29 and the module
// was flattened straight after. The Kotlin sources remain in the history at commit 9cedab8.
