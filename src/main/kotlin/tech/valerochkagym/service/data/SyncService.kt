package tech.valerochkagym.service.data

import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.ChangesPage
import tech.valerochkagym.controller.model.PushRequest
import tech.valerochkagym.controller.model.PushResult
import tech.valerochkagym.controller.model.Record
import tech.valerochkagym.controller.model.Snapshot
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.data.OperationRepository
import tech.valerochkagym.repository.data.PostgresSyncRepository
import tech.valerochkagym.repository.data.RecordRepository
import tech.valerochkagym.repository.model.CatalogStateEntity
import tech.valerochkagym.repository.model.OperationEntity
import tech.valerochkagym.repository.model.OperationId
import tech.valerochkagym.repository.model.RecordEntity
import tech.valerochkagym.service.model.RecordKey
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.ObjectMapper

@Service
class SyncService(
  private val catalog: tech.valerochkagym.repository.catalog.CatalogStateRepository,
  private val standard: tech.valerochkagym.repository.catalog.StandardRepository,
  private val heads: HeadRepository,
  private val recordRows: RecordRepository,
  private val operations: OperationRepository,
  private val postgres: PostgresSyncRepository,
  private val tx: TransactionTemplate,
  private val json: ObjectMapper,
  private val validator: RecordValidator,
  private val crypto: Crypto,
  private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) {
  private fun head(user: UUID, exclusive: Boolean): Long {
    postgres.ensureHead(user)
    return (if (exclusive) heads.writeLock(user) else heads.readLock(user)).revision
  }

  private fun records(user: UUID): List<Record> =
    recordRows.findByUserIdOrderByKindAscIdAsc(user).map {
      Record(it.kind, it.id, it.revision, it.deleted, it.payload?.let(json::readTree))
    }

  fun snapshot(user: UUID, version: String? = "2"): Snapshot =
    tx.execute {
      requireVersion(catalog.readLock(), version)
      val revision = head(user, false)
      requireAccountVersion(user, version)
      Snapshot(revision, records(user))
    }!!

  fun push(user: UUID, incoming: PushRequest, version: String? = "2"): PushResult {
    val request =
      incoming.copy(
        changes =
          incoming.changes.map { it.copy(payload = it.payload?.takeUnless { node -> node.isNull }) }
      )
    if (request.changes.isEmpty() || request.changes.size > 1000)
      bad("Отправляйте от 1 до 1000 изменений")
    if (request.changes.map { RecordKey(it.kind, it.id) }.distinct().size != request.changes.size)
      bad("Объект повторяется в пакете")
    val serialized = json.writeValueAsString(request)
    if (serialized.toByteArray().size > 10 * 1024 * 1024)
      throw ApiException(413, "payload_too_large", "Пакет слишком большой")
    val hash = crypto.hash(serialized)
    return tx.execute {
      val catalogHead = catalog.readLock()
      requireVersion(catalogHead, version)
      val previous = head(user, true)
      requireAccountVersion(user, version)
      val operation = operations.findById(OperationId(user, request.operationId)).orElse(null)
      if (operation != null) {
        if (operation.requestHash != hash)
          throw ApiException(
            409,
            "operation_reused",
            "Идентификатор операции уже использован с другими данными",
          )
        return@execute PushResult(operation.revision)
      }
      val existing = records(user).associateBy { RecordKey(it.kind, it.id) }.toMutableMap()
      val commonRows = standard.findAllByOrderByKindAscIdAsc()
      val common =
        commonRows.associate {
          RecordKey(it.kind, it.id) to
            Record(it.kind, it.id, it.revision, false, json.readTree(it.payload))
        }
      val before = existing.toMap() + common
      val dependent =
        request.changes.any { c ->
          c.kind in setOf("exercise", "gym", "routine") ||
            c.payload?.let {
              validator.referencesOf(Record(c.kind, c.id, 0, c.deleted, it)).isNotEmpty()
            } == true
        }
      if (dependent && catalogHead.active && request.catalogRevision != catalogHead.revision)
        throw ApiException(
          409,
          "catalog_stale",
          "Обновите каталог перед сохранением зависимых данных",
        )
      val revision = previous + 1
      request.changes.forEach { change ->
        val key = RecordKey(change.kind, change.id)
        if (change.kind !in RecordValidator.kinds || change.baseRevision < 0)
          bad("Некорректный тип или версия объекта")
        if (common.keys.any { it.id == change.id })
          throw ApiException(
            403,
            "standard_read_only",
            "Создайте личную копию стандартного объекта",
          )
        val old = existing[key]
        if (change.kind == "workout" && !change.deleted) {
          val hasCoach =
            change.payload?.get("exercises")?.any { section ->
              section.get("sets")?.any { it.has("syncId") } == true
            } == true
          val hadCoach =
            old?.payload?.get("exercises")?.any { section ->
              section.get("sets")?.any { it.has("syncId") } == true
            } == true
          if (hasCoach && version != "3")
            throw ApiException(426, "client_update_required", "Обновите приложение для Live Coach")
          if (
            hadCoach &&
              change.payload?.get("exercises")?.any { section ->
                section.get("sets")?.any { !it.has("syncId") } == true
              } == true
          )
            throw ApiException(
              409,
              "revision_conflict",
              "Старая очередь не содержит данные Live Coach. Выберите актуальную версию",
            )
          if (hasCoach) heads.writeLock(user).minSyncVersion = 3
        }
        if ((old?.revision ?: 0) != change.baseRevision)
          throw ApiException(
            409,
            "revision_conflict",
            "Объект изменён на другом устройстве. Получите актуальные данные",
          )
        if (change.deleted && change.payload != null || !change.deleted && change.payload == null)
          bad("Некорректное содержимое изменения")
        if (!change.deleted) validator.validate(change.kind, change.payload!!)
        existing[key] = Record(change.kind, change.id, revision, change.deleted, change.payload)
      }
      if (
        existing.size > 20_000 ||
          existing.values.sumOf { it.payload?.toString()?.toByteArray()?.size?.toLong() ?: 0L } >
            16L * 1024 * 1024
      )
        throw ApiException(409, "account_limit", "Превышено количество объектов аккаунта")
      validator.references(
        existing + common,
        request.changes.map { RecordKey(it.kind, it.id) }.toSet(),
        before,
      )
      validator.archivedReferences(
        existing + common,
        before,
        commonRows.filter { it.archived }.map { RecordKey(it.kind, it.id) }.toSet(),
      )
      request.changes.forEach { change ->
        recordRows.save(
          RecordEntity(
            user,
            change.kind,
            change.id,
            revision,
            change.deleted,
            change.payload?.let(json::writeValueAsString),
          )
        )
      }
      request.changes
        .filter { it.kind == "workout" && it.deleted }
        .forEach {
          jdbc.update(
            "UPDATE coach_journal SET payload=NULL,deleted=TRUE WHERE user_id=? AND workout_id=?",
            user,
            it.id,
          )
        }
      heads.writeLock(user).revision = revision
      operations.save(OperationEntity(user, request.operationId, hash, revision))
      PushResult(revision)
    }!!
  }

  fun changes(
    user: UUID,
    after: Long,
    cursor: String?,
    limit: Int,
    version: String? = "2",
  ): ChangesPage {
    if (after < 0 || limit !in 1..1000) bad("Некорректная пагинация")
    return tx.execute {
      requireVersion(catalog.readLock(), version)
      val high = head(user, false)
      requireAccountVersion(user, version)
      val parts = cursor?.split(":")
      if (parts != null && parts.size != 3) bad("Некорректный курсор")
      val rev = parts?.get(0)?.toLongOrNull() ?: after
      val kind = parts?.get(1) ?: ""
      val id = parts?.get(2)?.let(UUID::fromString) ?: UUID(0, 0)
      if (rev < after || rev > high || (parts != null && kind !in RecordValidator.kinds))
        bad("Некорректный курсор")
      val rows =
        records(user)
          .filter {
            it.revision > after &&
              (it.revision > rev ||
                it.revision == rev &&
                  (it.kind > kind || it.kind == kind && it.id.toString() > id.toString()))
          }
          .sortedWith(
            compareBy<Record> { it.revision }.thenBy { it.kind }.thenBy { it.id.toString() }
          )
          .take(limit + 1)
      val page = rows.take(limit)
      ChangesPage(
        high,
        page,
        if (rows.size > limit) page.last().let { "${it.revision}:${it.kind}:${it.id}" } else null,
      )
    }!!
  }

  private fun requireAccountVersion(user: UUID, version: String?) {
    if (heads.readLock(user).minSyncVersion >= 3 && version != "3")
      throw ApiException(426, "client_update_required", "Обновите приложение для Live Coach")
  }

  private fun requireVersion(head: CatalogStateEntity, version: String?) {
    if (head.active && version !in setOf("2", "3"))
      throw ApiException(426, "client_update_required", "Обновите приложение для общего каталога")
  }
}
