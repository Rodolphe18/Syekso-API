package dev.rodolphe.accesscontrol.access;

record InvitationDto(
        String code,
        String title,
        String doorName,
        long validFromEpochMs,
        long validUntilEpochMs
) {
}
