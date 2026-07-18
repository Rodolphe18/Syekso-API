package dev.rodolphe.accesscontrol.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MongoDB documents. Modeled for a document store, not translated from tables:
 * - doors are *embedded* in their building — they belong to it and are always read with it;
 * - membership is a `buildingIds` array on the user — joining a building appends one id, and that
 *   array is the single source of truth for "which doors can I open".
 *
 * `_id` is Mongo's primary key. We map our string ids onto it via @SerialName("_id").
 */

@Serializable
data class UserDoc(
    @SerialName("_id") val id: String,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val buildingIds: List<String> = emptyList(),
)

@Serializable
data class BuildingDoc(
    @SerialName("_id") val id: String,
    val name: String,
    val doors: List<DoorDoc> = emptyList(),
)

@Serializable
data class DoorDoc(
    val id: String,
    val name: String,
    // What the physical lock advertises over BLE; the app matches a scan result to a door by this.
    val bleLocalName: String,
)

/**
 * A code the manager mails to a resident. Single-use: [redeemedByUserId] is set once at redeem and
 * then the code is spent. `_id` is the code string itself.
 */
@Serializable
data class ActivationCodeDoc(
    @SerialName("_id") val code: String,
    val buildingId: String,
    val redeemedByUserId: String? = null,
    val redeemedAtEpochMs: Long? = null,
)

/**
 * A single-use numeric PIN a resident issues for one door. Typed on the intercom keypad; consumed on
 * first successful validation, or expires at [expiresAtEpochMs]. `_id` is the PIN itself.
 */
@Serializable
data class PinCodeDoc(
    @SerialName("_id") val pin: String,
    val issuedByUserId: String,
    val buildingId: String,
    val doorId: String,
    val doorName: String,
    val doorBleLocalName: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val redeemedAtEpochMs: Long? = null,
)
