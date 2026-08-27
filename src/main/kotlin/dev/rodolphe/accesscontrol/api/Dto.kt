package dev.rodolphe.accesscontrol.api

import kotlinx.serialization.Serializable

/** Wire types. Kept separate from the DB documents so the storage shape can change without
 *  breaking the API contract the Android app depends on. */

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class UserDto(val id: String, val email: String, val displayName: String)

@Serializable
data class LoginResponse(val token: String, val user: UserDto)

@Serializable
data class ActivationRequest(val code: String)

@Serializable
data class BuildingDto(val id: String, val name: String)

@Serializable
data class DoorDto(
    val id: String,
    val name: String,
    val buildingId: String,
    val buildingName: String,
    val bleLocalName: String,
)

@Serializable
data class ActivationResponse(val building: BuildingDto, val doors: List<DoorDto>)

@Serializable
data class DoorsResponse(val doors: List<DoorDto>)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class CreatePinRequest(val doorId: String)

@Serializable
data class PinCodeDto(val pin: String, val doorName: String, val expiresAtEpochMs: Long)

@Serializable
data class PinCodesResponse(val codes: List<PinCodeDto>)

@Serializable
data class DirectoryEntry(val userId: String, val displayName: String)

@Serializable
data class DirectoryResponse(val residents: List<DirectoryEntry>)

@Serializable
data class CreateInvitationRequest(
    val title: String,
    val doorId: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)

@Serializable
data class InvitationDto(
    val code: String,
    val title: String,
    val doorName: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)

@Serializable
data class InvitationsResponse(val invitations: List<InvitationDto>)

@Serializable
data class IntercomValidateRequest(val pin: String)

@Serializable
data class IntercomValidateResponse(
    val allowed: Boolean,
    val doorName: String? = null,
    val doorBleLocalName: String? = null,
    val reason: String? = null,
)

/**
 * What actually happened at the door after /intercom/validate said yes. A single-use PIN is claimed
 * during validation so two intercoms can't race for it, which means a failed open would otherwise
 * burn the visitor's only code — this hands it back.
 */
@Serializable
data class IntercomOpenResultRequest(val pin: String, val success: Boolean)

@Serializable
data class IntercomOpenResultResponse(val released: Boolean)

@Serializable
data class FeedItemDto(val id: String, val seq: Int, val label: String, val createdAtEpochMs: Long)

@Serializable
data class OffsetPageResponse(val items: List<FeedItemDto>, val page: Int, val nextPage: Int)

@Serializable
data class CursorPageResponse(val items: List<FeedItemDto>, val nextCursor: String?)
