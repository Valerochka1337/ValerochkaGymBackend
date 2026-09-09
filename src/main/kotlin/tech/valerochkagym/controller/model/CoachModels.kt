package tech.valerochkagym.controller.model

import java.util.UUID
import tools.jackson.databind.JsonNode

data class CoachEntry(
  val id: UUID,
  val workoutId: UUID,
  val deviceId: UUID,
  val createdAt: Long,
  val payload: JsonNode,
)

data class CoachPush(val entries: List<CoachEntry>)

data class CoachPage(val entries: List<CoachEntry>, val nextCursor: String?, val watermark: Long)
