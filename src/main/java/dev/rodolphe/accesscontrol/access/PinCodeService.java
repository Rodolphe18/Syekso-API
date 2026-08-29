package dev.rodolphe.accesscontrol.access;

import dev.rodolphe.accesscontrol.doors.Building;
import dev.rodolphe.accesscontrol.doors.BuildingRepository;
import dev.rodolphe.accesscontrol.doors.Door;
import dev.rodolphe.accesscontrol.access.PinCode;
import dev.rodolphe.accesscontrol.access.PinCodeRepository;
import dev.rodolphe.accesscontrol.users.User;
import dev.rodolphe.accesscontrol.users.UserRepository;
import dev.rodolphe.accesscontrol.access.CreateInvitationRequest;
import dev.rodolphe.accesscontrol.access.InvitationDto;
import dev.rodolphe.accesscontrol.access.InvitationsResponse;
import dev.rodolphe.accesscontrol.access.PinCodeDto;
import dev.rodolphe.accesscontrol.access.PinCodesResponse;
import dev.rodolphe.accesscontrol.shared.BadRequestException;
import dev.rodolphe.accesscontrol.shared.NotFoundException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

/** Issuing and listing the access codes a resident hands out. */
@Service
class PinCodeService {

    private static final long PIN_TTL_MS = 15 * 60 * 1000L;
    private static final int PIN_ALLOCATION_ATTEMPTS = 10;

    /**
     * SecureRandom rather than a plain Random: these six digits are the only thing standing between a
     * stranger and an open door, and a predictable generator would make them guessable. The Kotlin
     * server used {@code (100000..999999).random()}, which is not seeded securely.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final BuildingRepository buildings;
    private final PinCodeRepository pinCodes;

    public PinCodeService(UserRepository users, BuildingRepository buildings, PinCodeRepository pinCodes) {
        this.users = users;
        this.buildings = buildings;
        this.pinCodes = pinCodes;
    }

    /** A door together with the building it belongs to — the pair a lookup has to return. */
    private record OwnedDoor(Building building, Door door) {
    }

    public PinCodeDto createPinCode(String userId, String doorId) {
        OwnedDoor owned = requireOwnedDoor(userId, doorId);
        long now = System.currentTimeMillis();
        long expiresAt = now + PIN_TTL_MS;

        PinCode code = PinCode.singleUse(
                allocateUniquePin(), userId, owned.building().id(), owned.door(), now, expiresAt);
        // insert, not save: save would upsert, so a PIN that somehow collided with an existing one
        // would silently overwrite someone else's live code. insert fails loudly instead.
        pinCodes.insert(code);

        return new PinCodeDto(code.pin(), owned.door().name(), expiresAt);
    }

    public PinCodesResponse listPinCodes(String userId) {
        List<PinCodeDto> codes = pinCodes.findUnredeemedByIssuer(userId, System.currentTimeMillis())
                .stream()
                .map(code -> new PinCodeDto(code.pin(), code.doorName(), code.expiresAtEpochMs()))
                .toList();
        return new PinCodesResponse(codes);
    }

    public InvitationDto createInvitation(String userId, CreateInvitationRequest request) {
        // The one rule Bean Validation cannot state on a single field.
        if (request.validUntilEpochMs() <= request.validFromEpochMs()) {
            throw new BadRequestException("Fenêtre de validité invalide");
        }
        OwnedDoor owned = requireOwnedDoor(userId, request.doorId());
        String title = request.title().trim();

        PinCode code = PinCode.invitation(
                allocateUniquePin(), userId, owned.building().id(), owned.door(),
                System.currentTimeMillis(), title,
                request.validFromEpochMs(), request.validUntilEpochMs());
        pinCodes.insert(code);

        return new InvitationDto(code.pin(), title, owned.door().name(),
                request.validFromEpochMs(), request.validUntilEpochMs());
    }

    public InvitationsResponse listInvitations(String userId) {
        List<InvitationDto> invitations = pinCodes.findMultiUseByIssuer(userId, System.currentTimeMillis())
                .stream()
                .map(code -> new InvitationDto(
                        code.pin(),
                        code.title() == null ? "" : code.title(),
                        code.doorName(),
                        code.validFromEpochMs(),
                        code.expiresAtEpochMs()))
                .toList();
        return new InvitationsResponse(invitations);
    }

    /**
     * Finds a door the caller is actually entitled to, and refuses otherwise.
     *
     * <p>This is the authorisation check of the whole feature, and it is intentionally not expressed
     * as a security rule: "may this resident issue a code for that door" depends on data, not on a URL
     * pattern. The search starts from the buildings the user has joined, so a door outside them can
     * never be found — a resident cannot mint a PIN for someone else's building by guessing an id.
     */
    private OwnedDoor requireOwnedDoor(String userId, String doorId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        return buildings.findAllById(user.buildingIds()).stream()
                .flatMap(building -> building.doors().stream()
                        .filter(door -> door.doorId().equals(doorId))
                        .map(door -> new OwnedDoor(building, door)))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Porte introuvable"));
    }

    /**
     * Draws six-digit codes until one is free. Bounded on purpose: with a nearly full keyspace an
     * unbounded loop would spin forever, so it gives up loudly instead — a 500 that says the pool is
     * exhausted beats a request that never returns.
     */
    private String allocateUniquePin() {
        for (int attempt = 0; attempt < PIN_ALLOCATION_ATTEMPTS; attempt++) {
            String candidate = String.valueOf(100_000 + RANDOM.nextInt(900_000));
            if (pinCodes.findById(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique PIN after "
                + PIN_ALLOCATION_ATTEMPTS + " attempts");
    }
}
