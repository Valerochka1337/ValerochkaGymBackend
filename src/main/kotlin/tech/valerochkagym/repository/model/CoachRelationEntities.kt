package tech.valerochkagym.repository.model

import java.time.Instant
import java.util.UUID

data class CoachRelationRow(
  val id: UUID,
  val coachId: UUID,
  val recipientId: UUID,
  val state: String,
  val calendar: Boolean,
  val completedWorkouts: Boolean,
  val createdAt: Instant,
  val revokedAt: Instant?,
  val liveCoachId: UUID?,
  val liveRecipientId: UUID?,
)

data class CoachInvitationRow(
  val id: UUID,
  val coachId: UUID,
  val expiresAt: Instant,
  val consumerId: UUID?,
  val relationId: UUID?,
  val liveCoachId: UUID?,
)
