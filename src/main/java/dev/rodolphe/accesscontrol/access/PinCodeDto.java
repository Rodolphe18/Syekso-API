package dev.rodolphe.accesscontrol.access;

/** A single-use PIN as the resident sees it. Never exposes who issued it or which building it opens. */
record PinCodeDto(String pin, String doorName, long expiresAtEpochMs) {
}
