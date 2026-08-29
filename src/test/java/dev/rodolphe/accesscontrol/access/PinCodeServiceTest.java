package dev.rodolphe.accesscontrol.access;

import dev.rodolphe.accesscontrol.doors.Building;
import dev.rodolphe.accesscontrol.doors.BuildingRepository;
import dev.rodolphe.accesscontrol.doors.Door;
import dev.rodolphe.accesscontrol.access.PinCode;
import dev.rodolphe.accesscontrol.access.PinCodeRepository;
import dev.rodolphe.accesscontrol.users.User;
import dev.rodolphe.accesscontrol.users.UserRepository;
import dev.rodolphe.accesscontrol.access.PinCodeDto;
import dev.rodolphe.accesscontrol.shared.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The door-ownership check is the access-control decision of this service, and it is deliberately not
 * expressed as a security rule: whether a resident may issue a code for a door depends on data, not
 * on a URL pattern. Which means only a test like this one covers it.
 */
@ExtendWith(MockitoExtension.class)
class PinCodeServiceTest {

    @Mock private UserRepository users;
    @Mock private BuildingRepository buildings;
    @Mock private PinCodeRepository pinCodes;

    @InjectMocks private PinCodeService service;

    private static final Door HALL = new Door("door-hall", "Porte d'entrée", "OSKEY-HALL-01");

    @Test
    @DisplayName("issuing a PIN for a door of a building the resident joined")
    void issuesForOwnDoor() {
        given(users.findById("user-rodolphe"))
                .willReturn(Optional.of(new User("user-rodolphe", "r@e.com", "hash", "Rodolphe", List.of("bld-1"))));
        given(buildings.findAllById(List.of("bld-1")))
                .willReturn(List.of(new Building("bld-1", "Résidence", List.of(HALL))));
        given(pinCodes.findById(anyString())).willReturn(Optional.empty());

        PinCodeDto issued = service.createPinCode("user-rodolphe", "door-hall");

        assertEquals("Porte d'entrée", issued.doorName());
        assertEquals(6, issued.pin().length());

        // insert, never save: save would upsert, and a colliding PIN would silently overwrite a live
        // code belonging to someone else.
        var written = ArgumentCaptor.forClass(PinCode.class);
        verify(pinCodes).insert(written.capture());
        assertEquals("OSKEY-HALL-01", written.getValue().doorBleLocalName());
        assertTrue(written.getValue().singleUse());
    }

    @Test
    @DisplayName("a door in a building the resident has not joined is invisible to them")
    void refusesForeignDoor() {
        given(users.findById("user-rodolphe"))
                .willReturn(Optional.of(new User("user-rodolphe", "r@e.com", "hash", "Rodolphe", List.of("bld-1"))));
        given(buildings.findAllById(List.of("bld-1")))
                .willReturn(List.of(new Building("bld-1", "Résidence", List.of(HALL))));

        // The id is real — it just belongs to somebody else's building. Guessing it must lead nowhere.
        var refused = assertThrows(NotFoundException.class,
                () -> service.createPinCode("user-rodolphe", "door-du-voisin"));

        assertEquals("Porte introuvable", refused.getMessage());
        verify(pinCodes, never()).insert(org.mockito.ArgumentMatchers.any(PinCode.class));
    }

    @Test
    @DisplayName("a resident who has joined nothing can issue nothing")
    void refusesWhenNoBuildingJoined() {
        given(users.findById("user-rodolphe"))
                .willReturn(Optional.of(new User("user-rodolphe", "r@e.com", "hash", "Rodolphe", List.of())));
        given(buildings.findAllById(List.of())).willReturn(List.of());

        assertThrows(NotFoundException.class, () -> service.createPinCode("user-rodolphe", "door-hall"));
    }

    @Test
    @DisplayName("an unknown caller is refused before any door is looked at")
    void refusesUnknownUser() {
        given(users.findById("fantome")).willReturn(Optional.empty());

        var refused = assertThrows(NotFoundException.class,
                () -> service.createPinCode("fantome", "door-hall"));

        assertEquals("Utilisateur introuvable", refused.getMessage());
    }
}
