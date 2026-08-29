package dev.rodolphe.accesscontrol.doors;

import dev.rodolphe.accesscontrol.doors.ActivationCode;
import dev.rodolphe.accesscontrol.doors.ActivationCodeRepository;
import dev.rodolphe.accesscontrol.doors.Building;
import dev.rodolphe.accesscontrol.doors.BuildingRepository;
import dev.rodolphe.accesscontrol.users.User;
import dev.rodolphe.accesscontrol.users.UserRepository;
import dev.rodolphe.accesscontrol.doors.ActivationResponse;
import dev.rodolphe.accesscontrol.doors.BuildingDto;
import dev.rodolphe.accesscontrol.doors.DoorDto;
import dev.rodolphe.accesscontrol.doors.DoorsResponse;
import dev.rodolphe.accesscontrol.shared.ConflictException;
import dev.rodolphe.accesscontrol.shared.NotFoundException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;

/** What a signed-in resident can do with their own buildings and doors. */
@Service
public class ResidentService {

    private final UserRepository users;
    private final BuildingRepository buildings;
    private final ActivationCodeRepository activationCodes;
    private final MongoTemplate mongo;

    public ResidentService(UserRepository users,
                           BuildingRepository buildings,
                           ActivationCodeRepository activationCodes,
                           MongoTemplate mongo) {
        this.users = users;
        this.buildings = buildings;
        this.activationCodes = activationCodes;
        this.mongo = mongo;
    }

    /** Every door the resident can open, across all the buildings they have joined. */
    public DoorsResponse doorsOf(String userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        // findAllById is inherited from MongoRepository — the counterpart of Filters.in("_id", ids).
        List<DoorDto> doors = buildings.findAllById(user.buildingIds()).stream()
                .flatMap(building -> toDoorDtos(building).stream())
                .toList();
        return new DoorsResponse(doors);
    }

    /**
     * Redeems an activation code: attaches its building to the resident and returns the building with
     * its doors. The pivot of the whole onboarding flow.
     *
     * <p>Redeeming a code you already redeemed yourself is allowed and idempotent — an app that
     * retries after a lost response must not be punished for it. Only a code held by <em>someone
     * else</em> is a conflict.
     */
    public ActivationResponse redeemActivation(String userId, String rawCode) {
        String code = rawCode.trim();

        ActivationCode activation = activationCodes.findById(code)
                .orElseThrow(() -> new NotFoundException("Code d'activation inconnu"));

        if (activation.redeemedByUserId() != null && !activation.redeemedByUserId().equals(userId)) {
            throw new ConflictException("Code déjà utilisé");
        }

        if (activation.redeemedByUserId() == null && !claim(code, userId)) {
            // Someone claimed it between our read and our write. The condition inside the update is
            // what makes that detectable rather than silently overwriting their claim.
            throw new ConflictException("Code déjà utilisé");
        }

        // addToSet, not push: joining the same building twice must not duplicate the entry.
        mongo.updateFirst(
                new Query(Criteria.where("_id").is(userId)),
                new Update().addToSet("buildingIds", activation.buildingId()),
                User.class);

        Building building = buildings.findById(activation.buildingId())
                // An activation code pointing at a building that does not exist is corrupt data, not a
                // client mistake. Thrown as an IllegalStateException so the catch-all logs it with its
                // stack trace and answers a generic 500 — the detail belongs in the server log, not in
                // the response. (The Kotlin server returned the reason to the caller; this does not.)
                .orElseThrow(() -> new IllegalStateException(
                        "Immeuble introuvable pour le code " + code + " -> " + activation.buildingId()));

        return new ActivationResponse(
                new BuildingDto(building.id(), building.name()),
                toDoorDtos(building));
    }

    /** Claims the code only if nobody holds it, and reports whether this call is the one that won. */
    private boolean claim(String code, String userId) {
        Query query = new Query(Criteria.where("_id").is(code).and("redeemedByUserId").isNull());
        Update update = new Update()
                .set("redeemedByUserId", userId)
                .set("redeemedAtEpochMs", System.currentTimeMillis());
        return mongo.updateFirst(query, update, ActivationCode.class).getModifiedCount() > 0;
    }

    /** Flattens a building's embedded doors, copying the building's identity onto each one. */
    private static List<DoorDto> toDoorDtos(Building building) {
        return building.doors().stream()
                .map(door -> new DoorDto(
                        door.doorId(), door.name(), building.id(), building.name(), door.bleLocalName()))
                .toList();
    }
}
