package tech.valerochkagym.controller.model

import java.util.UUID
import tools.jackson.databind.JsonNode

data class AdminEdit(
  val operationId: UUID,
  val baseRevision: Long,
  val payload: JsonNode,
  val reason: String,
)

data class AdminAction(val operationId: UUID, val reason: String)

data class AdminPage(val items: List<Map<String, Any?>>, val hasMore: Boolean, val offset: Int)
