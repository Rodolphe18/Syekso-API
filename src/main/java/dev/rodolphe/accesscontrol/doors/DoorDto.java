package dev.rodolphe.accesscontrol.doors;

/**
 * A door as the app sees it: flattened, with its building's id and name copied in. The stored
 * document nests doors inside their building, but the app's list screen shows doors across several
 * buildings at once, so it needs each row to stand alone.
 */
record DoorDto(
        String id,
        String name,
        String buildingId,
        String buildingName,
        String bleLocalName
) {
}
