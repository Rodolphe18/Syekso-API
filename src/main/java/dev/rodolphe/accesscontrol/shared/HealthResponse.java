package dev.rodolphe.accesscontrol.shared;

/**
 * The Kotlin original was {@code data class HealthResponse(val status: String)}. A record is its
 * direct counterpart: immutable, with the constructor, accessors, equals, hashCode and toString
 * generated. Jackson reads records natively, which is why this module needs no extra module the way
 * a Kotlin data class needed jackson-module-kotlin.
 */
record HealthResponse(String status) {
}
