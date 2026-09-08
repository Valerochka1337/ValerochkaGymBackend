package tech.valerochkagym.controller.model

import java.util.UUID
import tools.jackson.databind.JsonNode

data class CatalogRecord(
  val kind: String,
  val id: String,
  val revision: Long,
  val archived: Boolean,
  val payload: JsonNode,
)

data class CatalogSnapshot(
  val active: Boolean,
  val revision: Long,
  val records: List<CatalogRecord>,
  val equipment: List<CatalogRecord>,
)

data class StandardEdit(
  val operationId: UUID,
  val baseRevision: Long,
  val reason: String,
  val payload: JsonNode,
)

data class StandardArchive(
  val operationId: UUID,
  val baseRevision: Long,
  val reason: String,
  val archived: Boolean = true,
)

data class CatalogPage(val items: List<CatalogRecord>, val hasMore: Boolean, val offset: Int)
