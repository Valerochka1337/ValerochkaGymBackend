package tech.valerochkagym.service.catalog

import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.Record
import tech.valerochkagym.repository.admin.AuditRepository
import tech.valerochkagym.repository.auth.UserRepository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.data.PostgresSyncRepository
import tech.valerochkagym.repository.data.RecordRepository
import tech.valerochkagym.repository.model.AuditEntity
import tech.valerochkagym.repository.model.StandardEntity
import tech.valerochkagym.service.data.RecordValidator
import tech.valerochkagym.service.model.RecordKey
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.ObjectMapper

@Service
class CatalogMigration(
  private val users: UserRepository,
  private val state: CatalogStateRepository,
  private val standard: StandardRepository,
  private val records: RecordRepository,
  private val heads: HeadRepository,
  private val postgres: PostgresSyncRepository,
  private val audits: AuditRepository,
  private val validator: RecordValidator,
  private val tx: TransactionTemplate,
  private val json: ObjectMapper,
  private val crypto: Crypto,
  private val clock: Clock,
) {
  /** No live account data moves during Liquibase startup; this is an explicit operator command. */
  fun run(
    apply: Boolean,
    source: UUID?,
    actor: UUID?,
    reason: String,
    backupConfirmed: Boolean,
    clientReady: Boolean,
  ): Map<String, Any?> =
    tx.execute { transaction ->
      val head = state.writeLock()
      if (head.active) {
        if (source != null && source != head.sourceUserId)
          bad("Каталог уже перенесён из другого аккаунта")
        return@execute mapOf(
          "status" to "already_active",
          "sourceUserId" to head.sourceUserId,
          "revision" to head.revision,
        )
      }
      val sourceId =
        source
          ?: users.findAll().singleOrNull()?.id
          ?: bad("Укажите UUID источника: аккаунтов не один")
      if (!users.existsById(sourceId)) bad("Аккаунт источника не найден")
      postgres.ensureHead(sourceId)
      heads.writeLock(sourceId)
      val personal = records.findByUserIdOrderByKindAscIdAsc(sourceId)
      val moving = personal.filter { !it.deleted && it.kind in setOf("exercise", "gym") }
      if (moving.isEmpty()) bad("Нет действующих упражнений и залов для переноса")
      val movingKeys = moving.map { Triple(it.userId, it.kind, it.id) }.toSet()
      if (
        records.findByIdIn(moving.map { it.id }).any {
          Triple(it.userId, it.kind, it.id) !in movingKeys
        }
      )
        bad(
          "UUID источника также используется другой личной записью. Разрешите конфликт перед переносом"
        )
      val current = standard.findAllByOrderByKindAscIdAsc()
      if (moving.any { m -> current.any { it.id == m.id } })
        bad("UUID переноса уже занят в общем каталоге")
      val graph =
        (current.map { Record(it.kind, it.id, it.revision, false, json.readTree(it.payload)) } +
            moving.map { Record(it.kind, it.id, it.revision, false, json.readTree(it.payload!!)) })
          .associateBy { RecordKey(it.kind, it.id) }
      moving.forEach { validator.validate(it.kind, json.readTree(it.payload!!)) }
      validator.references(graph, emptySet())
      // Preserve private templates/history verbatim, but ensure that every reference still
      // resolves.
      val after =
        personal
          .filter { it !in moving }
          .map { Record(it.kind, it.id, it.revision, it.deleted, it.payload?.let(json::readTree)) }
          .associateBy { RecordKey(it.kind, it.id) } + graph
      validator.references(after, emptySet())
      val result =
        mapOf(
          "status" to if (apply) "applied" else "checked",
          "sourceUserId" to sourceId,
          "exercises" to moving.count { it.kind == "exercise" },
          "gyms" to moving.count { it.kind == "gym" },
          "privateRecordsRetained" to (personal.size - moving.size),
          "revision" to if (apply) head.revision + 1 else head.revision,
        )
      if (!apply) {
        transaction.setRollbackOnly()
        return@execute result
      }
      if (!backupConfirmed || !clientReady)
        bad("Для применения подтвердите резервную копию и завершённую синхронизацию нового клиента")
      if (reason.trim().length !in 3..500) bad("Укажите причину переноса")
      val author =
        actor?.let { users.findById(it).orElse(null) } ?: bad("Укажите UUID автора переноса")
      if (!author.isAdmin || !author.emailVerified)
        bad("Автор переноса должен быть администратором")
      val revision = head.revision + 1
      moving.forEach { old ->
        standard.save(StandardEntity(old.kind, old.id, revision, false, old.payload!!))
        val operation =
          UUID.nameUUIDFromBytes("catalog-migration:$sourceId:${old.kind}:${old.id}".toByteArray())
        audits.save(
          AuditEntity(
            actorId = author.id,
            actorEmail = author.email,
            userId = null,
            action = "catalog_migrate",
            kind = old.kind,
            recordId = old.id,
            operationId = operation,
            requestHash = crypto.hash(old.payload!!),
            reason = reason.trim(),
            beforePayload =
              json.writeValueAsString(
                mapOf("owner" to sourceId, "payload" to json.readTree(old.payload!!))
              ),
            afterPayload = old.payload,
            revision = revision,
          )
        )
        records.delete(old)
      }
      head.revision = revision
      head.active = true
      head.sourceUserId = sourceId
      head.activatedAt = clock.instant()
      // Personal head stays independent: migrated objects are reclassified by the v2 snapshot,
      // not represented as personal deletions, and historical operation acknowledgements survive.
      result
    }
}
