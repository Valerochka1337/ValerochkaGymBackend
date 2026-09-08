package tech.valerochkagym.controller.model

import java.util.UUID
import tools.jackson.databind.JsonNode

data class Change(
  val kind: String,
  val id: UUID,
  val baseRevision: Long,
  val deleted: Boolean = false,
  val payload: JsonNode? = null,
)

data class PushRequest(
  val operationId: UUID,
  val changes: List<Change>,
  val catalogRevision: Long? = null,
)

data class PushResult(val revision: Long)

data class Record(
  val kind: String,
  val id: UUID,
  val revision: Long,
  val deleted: Boolean,
  val payload: JsonNode?,
)

data class Snapshot(val revision: Long, val records: List<Record>)

data class ChangesPage(val revision: Long, val records: List<Record>, val nextCursor: String?)
