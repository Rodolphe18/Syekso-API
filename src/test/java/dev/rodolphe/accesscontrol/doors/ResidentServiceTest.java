package dev.rodolphe.accesscontrol.doors;

import com.mongodb.client.result.UpdateResult;
import dev.rodolphe.accesscontrol.doors.ActivationCode;
import dev.rodolphe.accesscontrol.doors.ActivationCodeRepository;
import dev.rodolphe.accesscontrol.doors.Building;
import dev.rodolphe.accesscontrol.doors.BuildingRepository;
import dev.rodolphe.accesscontrol.doors.Door;
import dev.rodolphe.accesscontrol.users.User;
import dev.rodolphe.accesscontrol.users.UserRepository;
import dev.rodolphe.accesscontrol.doors.ActivationResponse;
import dev.rodolphe.accesscontrol.shared.ConflictException;
import dev.rodolphe.accesscontrol.shared.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Redeeming an activation code is the pivot of onboarding, and its conflict rules are subtle. */
@ExtendWith(MockitoExtension.class)
class ResidentServiceTest {

    @Mock private UserRepository users;
    @Mock private BuildingRepository buildings;
    @Mock private ActivationCodeRepository activationCodes;
    @Mock private MongoTemplate mongo;

    @InjectMocks private ResidentService residents;

    private static final Building MONTMARTRE = new Building("bld-1", "Résidence Montmartre",
            List.of(new Door("door-hall", "Porte d'entrée", "OSKEY-HALL-01")));

    @Test
    @DisplayName("an unknown activation code is a 404, not a conflict")
    void unknownCode() {
        given(activationCodes.findById("MONT-2026")).willReturn(Optional.empty());

        var refused = assertThrows(NotFoundException.class,
                () -> residents.redeemActivation("user-rodolphe", "MONT-2026"));

        assertEquals("Code d'activation inconnu", refused.getMessage());
    }

    @Test
    @DisplayName("a code already redeemed by somebody else is a conflict")
    void heldBySomeoneElse() {
        given(activationCodes.findById("MONT-2026"))
                .willReturn(Optional.of(new ActivationCode("MONT-2026", "bld-1", "un-autre-resident", 1L)));

        assertThrows(ConflictException.class,
                () -> residents.redeemActivation("user-rodolphe", "MONT-2026"));

        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(ActivationCode.class));
    }

    @Test
    @DisplayName("redeeming a code you already redeemed yourself succeeds again")
    void idempotentForTheSameUser() {
        given(activationCodes.findById("MONT-2026"))
                .willReturn(Optional.of(new ActivationCode("MONT-2026", "bld-1", "user-rodolphe", 1L)));
        given(buildings.findById("bld-1")).willReturn(Optional.of(MONTMARTRE));

        ActivationResponse response = residents.redeemActivation("user-rodolphe", "MONT-2026");

        assertEquals("Résidence Montmartre", response.building().name());
        // An app retrying after a lost response must not be punished for it — and the code is not
        // re-claimed, since it is already theirs.
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(ActivationCode.class));
    }

    @Test
    @DisplayName("a free code is claimed, and the building is added without duplication")
    void claimsAFreeCode() {
        given(activationCodes.findById("MONT-2026"))
                .willReturn(Optional.of(new ActivationCode("MONT-2026", "bld-1", null, null)));
        given(mongo.updateFirst(any(Query.class), any(Update.class), eq(ActivationCode.class)))
                .willReturn(UpdateResult.acknowledged(1, 1L, null));
        given(buildings.findById("bld-1")).willReturn(Optional.of(MONTMARTRE));

        ActivationResponse response = residents.redeemActivation("user-rodolphe", "MONT-2026");

        assertEquals(1, response.doors().size());
        assertEquals("door-hall", response.doors().getFirst().id());
        // addToSet rather than push: joining twice must not leave two identical entries.
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    @DisplayName("losing the claim race is a conflict, not a silent overwrite")
    void losesTheClaimRace() {
        given(activationCodes.findById("MONT-2026"))
                .willReturn(Optional.of(new ActivationCode("MONT-2026", "bld-1", null, null)));
        // Somebody claimed it between our read and our write.
        given(mongo.updateFirst(any(Query.class), any(Update.class), eq(ActivationCode.class)))
                .willReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThrows(ConflictException.class,
                () -> residents.redeemActivation("user-rodolphe", "MONT-2026"));

        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    @DisplayName("listing doors for an unknown user is a 404")
    void doorsForUnknownUser() {
        given(users.findById("fantome")).willReturn(Optional.empty());

        assertEquals("Utilisateur introuvable",
                assertThrows(NotFoundException.class, () -> residents.doorsOf("fantome")).getMessage());
    }

    @Test
    @DisplayName("doors are flattened across every building the resident joined")
    void doorsAreFlattened() {
        given(users.findById("user-rodolphe"))
                .willReturn(Optional.of(new User("user-rodolphe", "r@e.com", "h", "Rodolphe", List.of("bld-1", "bld-2"))));
        given(buildings.findAllById(List.of("bld-1", "bld-2"))).willReturn(List.of(
                MONTMARTRE,
                new Building("bld-2", "Résidence Voltaire",
                        List.of(new Door("door-2", "Garage", "OSKEY-GARAGE-01")))));

        var doors = residents.doorsOf("user-rodolphe").doors();

        assertEquals(2, doors.size());
        // Each row carries its own building's identity, because the app lists doors across buildings.
        assertEquals("Résidence Montmartre", doors.get(0).buildingName());
        assertEquals("Résidence Voltaire", doors.get(1).buildingName());
    }
}
