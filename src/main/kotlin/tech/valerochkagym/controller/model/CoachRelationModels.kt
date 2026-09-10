package tech.valerochkagym.controller.model

import java.util.UUID

data class CoachRelationResponse(
  val relationId: UUID,
  val counterpartyId: UUID,
  val state: String,
  val calendar: Boolean,
  val completedWorkouts: Boolean,
  val createdAtMillis: Long,
  val revokedAtMillis: Long?,
)

data class CoachInvitationCreated(val inviteId: UUID, val token: String, val expiresAtMillis: Long)

data class CoachDirectoryPage(
  val items: List<CoachRelationResponse>,
  val nextCursor: String?,
  val directoryRevision: Long,
)

data class CoachProjectionPage(
  val items: List<Map<String, Any?>>,
  val nextCursor: String?,
  val recipientSyncRevision: Long,
)
