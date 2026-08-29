package dev.rodolphe.accesscontrol.doors;

import java.util.List;

record ActivationResponse(BuildingDto building, List<DoorDto> doors) {
}
