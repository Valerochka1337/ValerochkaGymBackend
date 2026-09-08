package tech.valerochkagym.service.catalog

import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.CatalogPage
import tech.valerochkagym.controller.model.CatalogRecord
import tech.valerochkagym.controller.model.CatalogSnapshot
import tech.valerochkagym.controller.model.PushResult
import tech.valerochkagym.controller.model.Record
import tech.valerochkagym.controller.model.StandardArchive
import tech.valerochkagym.controller.model.StandardEdit
import tech.valerochkagym.repository.admin.AuditRepository
import tech.valerochkagym.repository.auth.PostgresAuthRepository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.catalog.EquipmentRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.model.AuditEntity
import tech.valerochkagym.repository.model.EquipmentEntity
import tech.valerochkagym.repository.model.StandardEntity
import tech.valerochkagym.repository.model.StandardId
import tech.valerochkagym.service.admin.AdminService
import tech.valerochkagym.service.data.RecordValidator
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.model.RecordKey
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class CatalogService(
  private val state: CatalogStateRepository,
  private val standard: StandardRepository,
  private val personal: tech.valerochkagym.repository.data.RecordRepository,
  private val equipment: EquipmentRepository,
  private val audits: AuditRepository,
  private val postgres: PostgresAuthRepository,
  private val admin: AdminService,
  private val validator: RecordValidator,
  private val json: ObjectMapper,
  private val crypto: Crypto,
  private val tx: TransactionTemplate,
) {
  fun snapshot(): CatalogSnapshot =
    tx.execute {
      val head = state.readLock()
      CatalogSnapshot(
        head.active,
        head.revision,
        standard.findAllByOrderByKindAscIdAsc().map {
          CatalogRecord(
            it.kind,
            it.id.toString(),
            it.revision,
            it.archived,
            json.readTree(it.payload),
          )
        },
        equipment.findAllByOrderByIdAsc().map {
          CatalogRecord("equipment", it.id, it.revision, it.archived, json.readTree(it.payload))
        },
      )
    }

  fun list(kind: String, q: String, archived: Boolean, offset: Int, limit: Int): CatalogPage {
    if (
      kind !in setOf("exercise", "gym", "routine", "equipment") ||
        q.length > 200 ||
        offset !in 0..1_000_000 ||
        limit !in 1..100
    )
      bad("Некорректный фильтр")
    val snap = snapshot()
    val rows =
      (if (kind == "equipment") snap.equipment else snap.records)
        .filter {
          it.kind == kind &&
            it.archived == archived &&
            (it.payload["name"].asString().contains(q, true) || it.id == q)
        }
        .drop(offset)
        .take(limit + 1)
    return CatalogPage(rows.take(limit), rows.size > limit, offset)
  }

  fun record(kind: String, id: String): CatalogRecord {
    val snap = snapshot()
    return (snap.records + snap.equipment).firstOrNull { it.kind == kind && it.id == id }
      ?: throw ApiException(404, "not_found", "Запись каталога не найдена")
  }

  fun save(actor: Identity, kind: String, id: String, body: StandardEdit) =
    mutate(actor, kind, id, body.operationId, body.baseRevision, body.reason, body.payload, null)

  fun archive(actor: Identity, kind: String, id: String, body: StandardArchive) =
    mutate(actor, kind, id, body.operationId, body.baseRevision, body.reason, null, body.archived)

  private fun mutate(
    actor: Identity,
    kind: String,
    id: String,
    operation: UUID,
    baseRevision: Long,
    reason: String,
    payload: JsonNode?,
    archive: Boolean?,
  ): PushResult {
    if (reason.trim().length !in 3..500 || baseRevision < 0) bad("Укажите причину и ревизию")
    if (kind !in setOf("exercise", "gym", "routine", "equipment")) bad("Неизвестный тип каталога")
    val uuid =
      if (kind == "equipment") null
      else
        try {
          UUID.fromString(id).also { if (it.toString() != id) bad("Некорректный UUID") }
        } catch (e: IllegalArgumentException) {
          bad("Некорректный UUID")
        }
    if (kind == "equipment" && !id.matches(Regex("[a-z][a-z0-9_]{0,99}")))
      bad("Некорректный ID оборудования")
    val hash =
      crypto.hash(
        json.writeValueAsString(
          listOf("standard", kind, id, operation, baseRevision, reason, payload, archive)
        )
      )
    return tx.execute {
      admin.requireAdmin(actor.userId)
      postgres.lockActor(actor.userId)
      val head = state.writeLock()
      audits.findByActorIdAndOperationId(actor.userId, operation)?.let {
        if (it.requestHash != hash)
          throw ApiException(409, "operation_reused", "Идентификатор операции уже использован")
        return@execute PushResult(it.revision!!)
      }
      val row = uuid?.let { standard.findById(StandardId(kind, it)).orElse(null) }
      if (uuid != null && row == null && personal.uuidExists(uuid))
        bad("UUID уже используется личной записью. Для переноса используйте команду миграции")
      val eq = if (uuid == null) equipment.findById(id).orElse(null) else null
      val previousRevision = row?.revision ?: eq?.revision ?: 0
      if (previousRevision != baseRevision)
        throw ApiException(409, "revision_conflict", "Каталог изменён. Обновите страницу")
      if (archive != null && row == null && eq == null)
        throw ApiException(404, "not_found", "Запись не найдена")
      val beforePayload = row?.payload ?: eq?.payload
      val beforeArchived = row?.archived ?: eq?.archived ?: false
      val nextPayload = payload ?: json.readTree(beforePayload!!)
      val nextArchived = archive ?: beforeArchived
      if (kind == "equipment") validateEquipment(id, nextPayload)
      else validator.validate(kind, nextPayload)
      val before =
        standard.findAllByOrderByKindAscIdAsc().associate {
          RecordKey(it.kind, it.id) to
            Record(it.kind, it.id, it.revision, false, json.readTree(it.payload))
        }
      val revision = head.revision + 1
      if (uuid != null) {
        val key = RecordKey(kind, uuid)
        val after = before + (key to Record(kind, uuid, revision, false, nextPayload))
        validator.references(after, if (payload != null) setOf(key) else emptySet(), before)
        validator.archivedReferences(
          after,
          before,
          standard.findAll().filter { it.archived }.map { RecordKey(it.kind, it.id) }.toSet(),
        )
        standard.save(
          StandardEntity(kind, uuid, revision, nextArchived, json.writeValueAsString(nextPayload))
        )
      } else
        equipment.save(
          EquipmentEntity(id, revision, nextArchived, json.writeValueAsString(nextPayload))
        )
      head.revision = revision
      fun auditPayload(p: String?, archived: Boolean): String? =
        p?.let {
          json.writeValueAsString(
            mapOf("id" to id, "archived" to archived, "payload" to json.readTree(it))
          )
        }
      audits.save(
        AuditEntity(
          actorId = actor.userId,
          actorEmail = actor.email,
          userId = null,
          action = if (archive != null) "standard_archive" else "standard_save",
          kind = kind,
          recordId = uuid,
          operationId = operation,
          requestHash = hash,
          reason = reason.trim(),
          beforePayload = auditPayload(beforePayload, beforeArchived),
          afterPayload = auditPayload(json.writeValueAsString(nextPayload), nextArchived),
          revision = revision,
        )
      )
      PushResult(revision)
    }
  }

  private fun validateEquipment(id: String, n: JsonNode) {
    if (
      !n.isObject ||
        n.properties().any { it.key !in setOf("name", "group", "synonyms", "provides") }
    )
      bad("Некорректное оборудование")
    for (key in listOf("name", "group")) if (
      n[key]?.isString != true || n[key].asString().isBlank() || n[key].asString().length > 200
    )
      bad("Укажите $key")
    for (key in listOf("synonyms", "provides")) {
      val v = n[key] ?: bad("Укажите $key")
      if (
        !v.isArray ||
          v.size() > 200 ||
          v.any { !it.isString || it.asString().isBlank() || it.asString().length > 200 } ||
          v.toList().distinct().size != v.size()
      )
        bad("Некорректный $key")
    }
    val known = equipment.findAll().map { it.id }.toSet() + id
    if (n["provides"].none { it.asString() == id } || n["provides"].any { it.asString() !in known })
      bad("provides должен содержать собственный ID и существующие ID оборудования")
  }
}
