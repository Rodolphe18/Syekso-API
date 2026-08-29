package dev.rodolphe.accesscontrol.intercom;

import dev.rodolphe.accesscontrol.access.PinCode;
import dev.rodolphe.accesscontrol.access.PinCodeRepository;
import dev.rodolphe.accesscontrol.users.UserRepository;
import dev.rodolphe.accesscontrol.intercom.DirectoryEntry;
import dev.rodolphe.accesscontrol.intercom.DirectoryResponse;
import dev.rodolphe.accesscontrol.intercom.IntercomOpenResultResponse;
import dev.rodolphe.accesscontrol.intercom.IntercomValidateResponse;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * What the lobby intercom is allowed to ask the server.
 *
 * <p>This is where {@code MongoTemplate} appears alongside the repositories, and the reason is worth
 * stating: claiming and releasing a code are <em>conditional atomic updates</em> — "set this field
 * only if it still holds that value, and tell me whether you actually did". A repository interface
 * has no way to express a condition on the update, nor to report how many documents it touched.
 * Repositories for the ordinary reads, {@code MongoTemplate} for the two operations that need
 * atomicity.
 */
@Service
public class IntercomService {

    /**
     * How long after being claimed a single-use PIN can still be handed back — long enough to cover a
     * BLE scan-connect-write round trip, short enough that an earlier visitor's claim can never be
     * resurrected by anyone holding the intercom key.
     */
    private static final long CLAIM_RELEASE_WINDOW_MS = 120_000L;

    private final PinCodeRepository pinCodes;
    private final UserRepository users;
    private final MongoTemplate mongo;

    public IntercomService(PinCodeRepository pinCodes, UserRepository users, MongoTemplate mongo) {
        this.pinCodes = pinCodes;
        this.users = users;
        this.mongo = mongo;
    }

    /**
     * Checks a PIN and, when it is single-use, claims it in the same breath.
     *
     * <p>Claiming during validation rather than after the door opens is deliberate: two intercoms
     * presented the same code at the same instant would otherwise both be told yes.
     */
    public IntercomValidateResponse validate(String rawPin) {
        String pin = rawPin.trim();
        long now = System.currentTimeMillis();

        PinCode code = pinCodes.findById(pin).orElse(null);
        if (code == null) {
            return IntercomValidateResponse.refused("Code inconnu");
        }
        if (now < code.validFromEpochMs()) {
            return IntercomValidateResponse.refused("Invitation pas encore active");
        }
        if (now > code.expiresAtEpochMs()) {
            return IntercomValidateResponse.refused("Code expiré");
        }
        // Safe to unbox: PinCode's compact constructor guarantees singleUse is never null.
        if (code.singleUse()) {
            if (code.redeemedAtEpochMs() != null || !claim(pin, now)) {
                return IntercomValidateResponse.refused("Code déjà utilisé");
            }
        }
        return IntercomValidateResponse.granted(code.doorName(), code.doorBleLocalName());
    }

    /**
     * Reserves a single-use code, and reports whether this call is the one that got it.
     *
     * <p>The condition {@code redeemedAtEpochMs is null} inside the update is the whole point: the
     * database decides the winner, so two concurrent callers cannot both succeed. Reading first and
     * writing second would leave a window between the two where both see the code as free.
     */
    private boolean claim(String pin, long now) {
        Query query = new Query(Criteria.where("_id").is(pin).and("redeemedAtEpochMs").isNull());
        Update update = new Update().set("redeemedAtEpochMs", now);
        return mongo.updateFirst(query, update, PinCode.class).getModifiedCount() > 0;
    }

    /**
     * Hands a claimed code back when the door never opened, so a radio glitch does not cost a visitor
     * their only PIN. A success leaves the claim standing — the code really was spent.
     *
     * <p>DIVERGENCE FROM THE KOTLIN SERVER, deliberate. The original matches {@code singleUse == true},
     * which in MongoDB does <em>not</em> match a document where the field is absent — and iteration 1
     * proved that such documents exist in this collection. Those legacy PINs could be claimed but
     * never released. Matching {@code singleUse != false} covers "absent or true" while still
     * excluding multi-use invitations, which is what the Kotlin default of {@code true} always meant.
     */
    public IntercomOpenResultResponse reportOpenResult(String rawPin, boolean success) {
        if (success) {
            return new IntercomOpenResultResponse(false);
        }
        long floor = System.currentTimeMillis() - CLAIM_RELEASE_WINDOW_MS;
        Query query = new Query(Criteria.where("_id").is(rawPin.trim())
                .and("singleUse").ne(false)
                .and("redeemedAtEpochMs").gte(floor));
        Update update = new Update().set("redeemedAtEpochMs", null);
        long released = mongo.updateFirst(query, update, PinCode.class).getModifiedCount();
        return new IntercomOpenResultResponse(released > 0);
    }

    /** The residents an intercom may ring, for its CONTACT list. */
    public DirectoryResponse directory(String buildingId) {
        List<DirectoryEntry> residents = users.findByBuildingIdsContaining(buildingId).stream()
                .map(user -> new DirectoryEntry(user.id(), user.displayName()))
                .toList();
        return new DirectoryResponse(residents);
    }
}
