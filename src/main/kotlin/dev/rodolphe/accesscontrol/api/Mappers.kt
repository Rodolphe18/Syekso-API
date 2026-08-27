package dev.rodolphe.accesscontrol.api

import dev.rodolphe.accesscontrol.db.BuildingDoc
import dev.rodolphe.accesscontrol.db.FeedItemDoc
import dev.rodolphe.accesscontrol.db.UserDoc

fun UserDoc.toFeedItemDto() = UserDto(id = id, email = email, displayName = displayName)

fun BuildingDoc.toBuildingDto() = BuildingDto(id = id, name = name)

fun BuildingDoc.toDoorDtos(): List<DoorDto> = doors.map {
    DoorDto(
        id = it.id,
        name = it.name,
        buildingId = id,
        buildingName = name,
        bleLocalName = it.bleLocalName,
    )
}

fun FeedItemDoc.toFeedItemDto() = FeedItemDto(id, seq, label, createdAtEpochMs)
