package tech.valerochkagym.service.ai

import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.controller.model.AiContextRevision
import tech.valerochkagym.repository.auth.SessionRepository
import tech.valerochkagym.repository.auth.UserRepository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.data.RecordRepository
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.ObjectMapper

data class AiCapturedContext(
  val revision: AiContextRevision,
  val catalog: String,
  val allowedIds: Set<String>,
  val profile: AiProfileContext? = null,
)

@Service
class AiContextReader(
  private val tx: TransactionTemplate,
  private val catalog: CatalogStateRepository,
  private val heads: HeadRepository,
  private val records: RecordRepository,
  private val standard: StandardRepository,
  private val users: UserRepository,
  private val sessions: SessionRepository,
  private val json: ObjectMapper,
  private val clock: Clock,
) {
  fun capture(
    identity: Identity,
    revision: Long,
    catalogRevision: Long,
    includeExercises: Boolean,
  ): AiCapturedContext =
    tx.execute {
      val common = catalog.readLock()
      // A client with no acknowledged owner head is not sync-ready. Do not create one here.
      if (!heads.existsById(identity.userId)) throw aiError("ai_context_stale")
      val head = heads.readLock(identity.userId)
      val session = sessions.findById(identity.sessionId).orElse(null) ?: unauthorized()
      val now = Instant.now()
      if (
        session.userId != identity.userId ||
          session.revokedAt != null ||
          !session.accessExpiresAt.isAfter(now) ||
          !session.refreshExpiresAt.isAfter(now) ||
          !users.existsById(identity.userId)
      )
        unauthorized()
      if (head.revision != revision || common.revision != catalogRevision)
        throw aiError("ai_context_stale")
      val personal =
        if (includeExercises) records.findByUserIdOrderByKindAscIdAsc(identity.userId)
        else emptyList()
      val profile =
        personal
          .singleOrNull { it.kind == "profile" && !it.deleted }
          ?.let { AiProfileContext.fromSaved(json.readTree(it.payload!!), clock) }
      val rows =
        if (includeExercises)
          personal
            .filter { it.kind == "exercise" && !it.deleted }
            .map { mapOf("id" to it.id.toString(), "payload" to json.readTree(it.payload!!)) } +
            standard
              .findAllByOrderByKindAscIdAsc()
              .filter { common.active && it.kind == "exercise" && !it.archived }
              .map { mapOf("id" to it.id.toString(), "payload" to json.readTree(it.payload)) }
        else emptyList()
      val serialized = json.writeValueAsString(rows)
      if (serialized.toByteArray(Charsets.UTF_8).size > 1024 * 1024)
        throw aiError("ai_context_too_large")
      AiCapturedContext(
        AiContextRevision(revision, catalogRevision),
        serialized,
        rows.map { it["id"] as String }.toSet(),
        profile,
      )
    }!!
}
