package dev.rodolphe.accesscontrol.intercom;

import com.mongodb.client.result.UpdateResult;
import dev.rodolphe.accesscontrol.doors.Door;
import dev.rodolphe.accesscontrol.access.PinCode;
import dev.rodolphe.accesscontrol.access.PinCodeRepository;
import dev.rodolphe.accesscontrol.users.UserRepository;
import dev.rodolphe.accesscontrol.intercom.IntercomValidateResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The branches of {@code validate} are the access-control decisions of the whole intercom: each one
 * is the difference between a door that opens and a door that does not.
 *
 * <p>Mocking {@link MongoTemplate} lets the claim be examined without a database — including the case
 * that matters most and cannot be reproduced by hand, where the atomic update reports that another
 * caller got the code first.
 */
@ExtendWith(MockitoExtension.class)
class IntercomServiceTest {

    private static final long HOUR = 3_600_000L;

    @Mock private PinCodeRepository pinCodes;
    @Mock private UserRepository users;
    @Mock private MongoTemplate mongo;

    @InjectMocks private IntercomService intercom;

    private static final Door DOOR = new Door("door-hall", "Porte d'entrée", "OSKEY-HALL-01");

    private PinCode code(boolean singleUse, Long redeemedAt, long validFrom, long expiresAt) {
        return new PinCode("123456", "user-rodolphe", "bld-montmartre",
                DOOR.doorId(), DOOR.name(), DOOR.bleLocalName(),
                0L, expiresAt, validFrom, singleUse, null, redeemedAt);
    }

    private void claimSucceeds() {
        given(mongo.updateFirst(any(Query.class), any(Update.class), eq(PinCode.class)))
                .willReturn(UpdateResult.acknowledged(1, 1L, null));
    }

    @Test
    @DisplayName("an unknown code is refused without touching the database further")
    void unknownCode() {
        given(pinCodes.findById("123456")).willReturn(Optional.empty());

        IntercomValidateResponse response = intercom.validate("123456");

        assertFalse(response.allowed());
        assertEquals("Code inconnu", response.reason());
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(PinCode.class));
    }

    @Test
    @DisplayName("an invitation whose window has not opened is refused")
    void notYetActive() {
        long future = System.currentTimeMillis() + HOUR;
        given(pinCodes.findById("123456")).willReturn(Optional.of(code(false, null, future, future + HOUR)));

        assertEquals("Invitation pas encore active", intercom.validate("123456").reason());
    }

    @Test
    @DisplayName("an expired code is refused")
    void expired() {
        long past = System.currentTimeMillis() - HOUR;
        given(pinCodes.findById("123456")).willReturn(Optional.of(code(true, null, 0, past)));

        assertEquals("Code expiré", intercom.validate("123456").reason());
    }

    @Test
    @DisplayName("a single-use code already redeemed is refused")
    void alreadyRedeemed() {
        given(pinCodes.findById("123456"))
                .willReturn(Optional.of(code(true, 1L, 0, System.currentTimeMillis() + HOUR)));

        assertEquals("Code déjà utilisé", intercom.validate("123456").reason());
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(PinCode.class));
    }

    @Test
    @DisplayName("a valid single-use code is granted and claimed in the same call")
    void grantsAndClaims() {
        given(pinCodes.findById("123456"))
                .willReturn(Optional.of(code(true, null, 0, System.currentTimeMillis() + HOUR)));
        claimSucceeds();

        IntercomValidateResponse response = intercom.validate("123456");

        assertTrue(response.allowed());
        assertEquals("OSKEY-HALL-01", response.doorBleLocalName());
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(PinCode.class));
    }

    @Test
    @DisplayName("losing the claim race is refused, even though the code looked free when read")
    void losesTheClaimRace() {
        given(pinCodes.findById("123456"))
                .willReturn(Optional.of(code(true, null, 0, System.currentTimeMillis() + HOUR)));
        // Another intercom claimed it between our read and our write. Nothing was modified.
        given(mongo.updateFirst(any(Query.class), any(Update.class), eq(PinCode.class)))
                .willReturn(UpdateResult.acknowledged(0, 0L, null));

        IntercomValidateResponse response = intercom.validate("123456");

        assertFalse(response.allowed(), "the same code must not open two doors");
        assertEquals("Code déjà utilisé", response.reason());
    }

    @Test
    @DisplayName("a multi-use invitation is granted without being claimed")
    void multiUseIsNotClaimed() {
        given(pinCodes.findById("123456"))
                .willReturn(Optional.of(code(false, null, 0, System.currentTimeMillis() + HOUR)));

        assertTrue(intercom.validate("123456").allowed());
        // The whole difference with a single-use code: nothing is consumed, so it works again.
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(PinCode.class));
    }

    @Test
    @DisplayName("a successful opening leaves the claim standing")
    void successKeepsTheClaim() {
        assertFalse(intercom.reportOpenResult("123456", true).released());
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(PinCode.class));
    }

    @Test
    @DisplayName("a failed opening hands the code back, matching singleUse != false")
    void failureReleases() {
        given(mongo.updateFirst(any(Query.class), any(Update.class), eq(PinCode.class)))
                .willReturn(UpdateResult.acknowledged(1, 1L, null));

        assertTrue(intercom.reportOpenResult("123456", false).released());

        // Guards the deliberate divergence from the Kotlin server: matching singleUse == true would
        // skip every document written before that field existed, and those codes could be claimed but
        // never released.
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateFirst(query.capture(), any(Update.class), eq(PinCode.class));
        assertTrue(query.getValue().getQueryObject().toJson().contains("$ne"),
                "the release must match singleUse != false, not == true");
    }
}
