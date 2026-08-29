package dev.rodolphe.accesscontrol.users;

/**
 * The resident as the API exposes them. Deliberately not the {@code User} document: that one carries
 * {@code passwordHash} and {@code buildingIds}, neither of which has any business crossing the wire.
 * This is the whole point of keeping wire types apart from storage types.
 */
public record UserDto(String id, String email, String displayName) {
}
