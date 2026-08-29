package dev.rodolphe.accesscontrol.config;

import dev.rodolphe.accesscontrol.doors.ActivationCode;
import dev.rodolphe.accesscontrol.doors.ActivationCodeRepository;
import dev.rodolphe.accesscontrol.doors.Building;
import dev.rodolphe.accesscontrol.doors.BuildingRepository;
import dev.rodolphe.accesscontrol.doors.Door;
import dev.rodolphe.accesscontrol.feed.FeedItem;
import dev.rodolphe.accesscontrol.feed.FeedItemRepository;
import dev.rodolphe.accesscontrol.users.User;
import dev.rodolphe.accesscontrol.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Puts a usable dataset in an empty database at startup — the port of {@code MongoStorage.seedIfEmpty()}
 * and {@code setupFeedItems()}, which the Kotlin server ran from {@code module()}.
 *
 * <p><strong>Why this had to be ported before the cutover.</strong> Spring can verify a password but
 * could not create one: {@code BCryptPasswordEncoder} was only ever asked to {@code matches}. Every
 * account in the Atlas database was written by the Kotlin server. Stop that server without this class
 * and a fresh database stays empty for good — no resident, therefore no login, therefore nothing.
 * Which is also what anyone cloning this repository would find.
 *
 * <p>The values are copied from the Kotlin seed rather than reinvented, because the ESP32 firmware
 * advertises {@code OSKEY-HALL-01} and the apps have {@code rodolphe@example.com} prefilled in their
 * login screen. Renaming anything here breaks a demo on real hardware.
 *
 * <p>{@code @Profile("!test")} for the same reason as the WebSocket container bean: {@code
 * @SpringBootTest} does run {@link ApplicationRunner} beans, and this one would try to reach a Mongo
 * server that no test has any business needing. It is also why {@code DataLayerCheck} was deleted in
 * iteration 5.
 */
@Component
@Profile("!test")
public class MongoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoSeeder.class);

    private static final String BUILDING_ID = "bld-montmartre";
    private static final int FEED_ROWS = 1000;

    /** Arbitrary fixed epoch, copied from the Kotlin seed so both servers generate the same rows. */
    private static final long FEED_BASE_EPOCH_MS = 1_700_000_000_000L;

    private final UserRepository users;
    private final BuildingRepository buildings;
    private final ActivationCodeRepository activationCodes;
    private final FeedItemRepository feedItems;
    private final MongoTemplate mongo;
    private final PasswordEncoder passwordEncoder;

    public MongoSeeder(UserRepository users,
                       BuildingRepository buildings,
                       ActivationCodeRepository activationCodes,
                       FeedItemRepository feedItems,
                       MongoTemplate mongo,
                       PasswordEncoder passwordEncoder) {
        this.users = users;
        this.buildings = buildings;
        this.activationCodes = activationCodes;
        this.feedItems = feedItems;
        this.mongo = mongo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty();
        setupFeedItems();
    }

    /**
     * One resident, one building with two doors, one unredeemed activation code — enough to demo
     * login → activation → doors. Activation codes are seeded because the manager issues them in the
     * real product, and there is no manager application.
     *
     * <p>Keyed on the users collection being empty, exactly as the Kotlin version was: the three
     * inserts belong to one dataset, and seeding half of it would be worse than seeding none.
     */
    private void seedIfEmpty() {
        if (users.count() > 0) {
            return;
        }

        buildings.insert(new Building(BUILDING_ID, "Résidence Montmartre", List.of(
                new Door("door-hall", "Porte d'entrée", "OSKEY-HALL-01"),
                new Door("door-garage", "Garage", "OSKEY-GARAGE-01"))));

        // The Kotlin server hashed with jbcrypt, this one with BCryptPasswordEncoder. Both write the
        // same $2a$ format, so a password created here is readable by either server — which is what
        // makes a rollback to Ktor possible right up to the last moment.
        users.insert(new User("user-rodolphe", "rodolphe@example.com",
                passwordEncoder.encode("password"), "Rodolphe", List.of()));

        // insert, not save: save would upsert and quietly reset a code someone had already redeemed.
        activationCodes.insert(new ActivationCode("MONT-2026", BUILDING_ID, null, null));

        log.info("Seeded an empty database: 1 resident, 1 building, 2 doors, activation code MONT-2026");
    }

    /**
     * The compound index the cursor query depends on, then a thousand rows if the collection is empty.
     *
     * <p>The index must match the feed's sort order exactly — {@code createdAtEpochMs} then {@code _id},
     * both descending — or the keyset query degrades from an index seek to a collection scan, which is
     * the very thing the pagination exercise exists to demonstrate. Creating it is idempotent.
     */
    private void setupFeedItems() {
        mongo.indexOps(FeedItem.class).createIndex(new Index()
                .on("createdAtEpochMs", Sort.Direction.DESC)
                .on("_id", Sort.Direction.DESC));

        if (feedItems.count() > 0) {
            return;
        }

        List<FeedItem> rows = IntStream.rangeClosed(1, FEED_ROWS)
                .mapToObj(seq -> new FeedItem(
                        UUID.randomUUID().toString(), seq, "Item #" + seq,
                        FEED_BASE_EPOCH_MS + seq * 1000L))
                .toList();
        mongo.insert(rows, FeedItem.class);

        log.info("Seeded {} feed rows", FEED_ROWS);
    }
}
