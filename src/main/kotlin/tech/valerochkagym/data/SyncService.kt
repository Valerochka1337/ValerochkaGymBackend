package tech.valerochkagym.data

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.auth.Crypto
import tech.valerochkagym.web.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class RecordKey(val kind: String, val id: UUID)

data class Change(
  val kind: String,
  val id: UUID,
  val baseRevision: Long,
  val deleted: Boolean = false,
  val payload: JsonNode? = null,
)

data class PushRequest(val operationId: UUID, val changes: List<Change>)

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

@Service
class SyncService(
  private val db: JdbcTemplate,
  private val tx: TransactionTemplate,
  private val json: ObjectMapper,
  private val validator: RecordValidator,
  private val crypto: Crypto,
) {
  private fun head(user: UUID, exclusive: Boolean): Long {
    db.update("INSERT INTO sync_heads(user_id) VALUES (?) ON CONFLICT DO NOTHING", user)
    return db.queryForObject(
      "SELECT revision FROM sync_heads WHERE user_id=? FOR ${if(exclusive) "UPDATE" else "SHARE"}",
      Long::class.java,
      user,
    )!!
  }

  private fun records(user: UUID): List<Record> =
    db.query(
      "SELECT kind,id,revision,deleted,payload::text FROM records WHERE user_id=? ORDER BY kind,id",
      { r, _ ->
        Record(
          r.getString(1),
          r.getObject(2, UUID::class.java),
          r.getLong(3),
          r.getBoolean(4),
          r.getString(5)?.let(json::readTree),
        )
      },
      user,
    )

  fun snapshot(user: UUID): Snapshot = tx.execute { Snapshot(head(user, false), records(user)) }!!

  fun push(user: UUID, incoming: PushRequest): PushResult {
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
      val previous = head(user, true)
      val operation =
        db
          .queryForList(
            "SELECT request_hash,revision FROM sync_operations WHERE user_id=? AND operation_id=?",
            user,
            request.operationId,
          )
          .firstOrNull()
      if (operation != null) {
        if (operation["request_hash"] != hash)
          throw ApiException(
            409,
            "operation_reused",
            "Идентификатор операции уже использован с другими данными",
          )
        return@execute PushResult(operation["revision"] as Long)
      }
      val existing = records(user).associateBy { RecordKey(it.kind, it.id) }.toMutableMap()
      val revision = previous + 1
      request.changes.forEach { change ->
        val key = RecordKey(change.kind, change.id)
        if (change.kind !in RecordValidator.kinds || change.baseRevision < 0)
          bad("Некорректный тип или версия объекта")
        val old = existing[key]
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
      validator.references(existing)
      request.changes.forEach { change ->
        db.update(
          """INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,?,?,?,?,?::jsonb)
                    ON CONFLICT(user_id,kind,id) DO UPDATE SET revision=EXCLUDED.revision,deleted=EXCLUDED.deleted,payload=EXCLUDED.payload""",
          user,
          change.kind,
          change.id,
          revision,
          change.deleted,
          change.payload?.let(json::writeValueAsString),
        )
      }
      db.update("UPDATE sync_heads SET revision=? WHERE user_id=?", revision, user)
      db.update(
        "INSERT INTO sync_operations(user_id,operation_id,request_hash,revision) VALUES (?,?,?,?)",
        user,
        request.operationId,
        hash,
        revision,
      )
      PushResult(revision)
    }!!
  }

  fun changes(user: UUID, after: Long, cursor: String?, limit: Int): ChangesPage {
    if (after < 0 || limit !in 1..1000) bad("Некорректная пагинация")
    return tx.execute {
      val high = head(user, false)
      val parts = cursor?.split(":")
      if (parts != null && parts.size != 3) bad("Некорректный курсор")
      val rev = parts?.get(0)?.toLongOrNull() ?: after
      val kind = parts?.get(1) ?: ""
      val id = parts?.get(2)?.let(UUID::fromString) ?: UUID(0, 0)
      if (rev < after || rev > high || (parts != null && kind !in RecordValidator.kinds))
        bad("Некорректный курсор")
      val rows =
        db.query(
          """SELECT kind,id,revision,deleted,payload::text FROM records
                WHERE user_id=? AND revision>? AND (revision,kind,id)>(?,?,?) ORDER BY revision,kind,id LIMIT ?""",
          { r, _ ->
            Record(
              r.getString(1),
              r.getObject(2, UUID::class.java),
              r.getLong(3),
              r.getBoolean(4),
              r.getString(5)?.let(json::readTree),
            )
          },
          user,
          after,
          rev,
          kind,
          id,
          limit + 1,
        )
      val page = rows.take(limit)
      ChangesPage(
        high,
        page,
        if (rows.size > limit) page.last().let { "${it.revision}:${it.kind}:${it.id}" } else null,
      )
    }!!
  }
}
