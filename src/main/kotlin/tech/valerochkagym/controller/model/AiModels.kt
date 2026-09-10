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

data class CalendarDraftRequest(
  val requestId: String,
  val expectedRevision: Long,
  val expectedCatalogRevision: Long,
  val startsAtMillis: Long,
  val timeZoneId: String,
  val gymIds: List<String>,
  val excludedExerciseIds: List<String>,
  val excludedEquipmentIds: List<String>,
  val priorityMuscles: List<String>,
  val includeNotes: Boolean,
  val availableDurationMinutes: Int,
  val currentState: String?,
  val preferences: String?,
)

data class CalendarDraftContext(
  val revision: Long,
  val catalogRevision: Long,
  val capturedAtMillis: Long,
)

data class CalendarDraftResponse(
  val requestId: String,
  val context: CalendarDraftContext,
  val proposal: ProposalResponse,
)
