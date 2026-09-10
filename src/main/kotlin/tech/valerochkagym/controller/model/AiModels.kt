package tech.valerochkagym.controller.model

import tools.jackson.databind.JsonNode

data class ExerciseDraftRequest(
  val requestId: String,
  val expectedRevision: Long,
  val expectedCatalogRevision: Long,
  val description: String,
)

data class InBodyDraftRequest(
  val requestId: String,
  val expectedRevision: Long,
  val expectedCatalogRevision: Long,
  val image: AiImage,
)

data class AiImage(val mediaType: String, val base64: String)

data class AiContextRevision(val revision: Long, val catalogRevision: Long)

data class AiDraftResponse(
  val requestId: String,
  val context: AiContextRevision,
  val result: JsonNode,
)

data class AiStatus(val schemaVersion: Int = 1, val availability: String, val actions: List<String>)
