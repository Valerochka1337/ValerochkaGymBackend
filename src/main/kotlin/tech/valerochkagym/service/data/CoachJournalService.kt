package tech.valerochkagym.service.data

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.data.PostgresSyncRepository
import tools.jackson.databind.ObjectMapper

/** Immutable account-scoped events, bounded independently from workout aggregate sync. */
@Service
class CoachJournalService(
  private val jdbc: JdbcTemplate,
  private val heads: HeadRepository,
  private val postgres: PostgresSyncRepository,
  private val tx: TransactionTemplate,
  private val json: ObjectMapper,
) {
  fun push(user: UUID, request: CoachPush, version: String?): Map<String, Int> {
    requireVersion(version)
    if (
      request.entries.isEmpty() ||
        request.entries.size > 100 ||
        request.entries.map { it.id }.distinct().size != request.entries.size
    )
      bad("Некорректная страница журнала")
    if (json.writeValueAsBytes(request).size > 512 * 1024)
      throw ApiException(413, "journal_page_limit", "Страница журнала слишком большая")
    request.entries.forEach {
      if (
        it.createdAt < 0 ||
          !it.payload.isObject ||
          json.writeValueAsBytes(it.payload).size > 64 * 1024
      )
        bad("Некорректная запись журнала")
    }
    return tx.execute {
      postgres.ensureHead(user)
      val head = heads.writeLock(user)
      var additionalBytes = 0L
      val newEntries =
        request.entries.filter { entry ->
          val alive =
            jdbc.queryForObject(
              "SELECT count(*) FROM records WHERE user_id=? AND kind='workout' AND id=? AND NOT deleted",
              Long::class.java,
              user,
              entry.workoutId,
            )!! > 0
          if (!alive)
            throw ApiException(409, "workout_deleted", "Тренировка отсутствует или удалена")
          val existing =
            jdbc
              .query(
                "SELECT id,workout_id,device_id,created_at,payload FROM coach_journal WHERE user_id=? AND id=?",
                { rs, _ ->
                  CoachEntry(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("workout_id", UUID::class.java),
                    rs.getObject("device_id", UUID::class.java),
                    rs.getLong("created_at"),
                    json.readTree(rs.getString("payload") ?: "null"),
                  )
                },
                user,
                entry.id,
              )
              .singleOrNull()
          if (existing != null && existing != entry)
            throw ApiException(
              409,
              "journal_conflict",
              "Запись журнала отличается на другом устройстве",
            )
          if (existing == null) additionalBytes += json.writeValueAsBytes(entry.payload).size
          existing == null
        }
      val bytes =
        jdbc.queryForObject(
          "SELECT COALESCE(sum(octet_length(payload::text)),0) FROM coach_journal WHERE user_id=? AND NOT deleted",
          Long::class.java,
          user,
        )!!
      val count =
        jdbc.queryForObject(
          "SELECT count(*) FROM coach_journal WHERE user_id=? AND NOT deleted",
          Long::class.java,
          user,
        )!!
      if (
        newEntries.isNotEmpty() &&
          (bytes + additionalBytes > 32L * 1024 * 1024 || count + newEntries.size > 100_000)
      )
        throw ApiException(
          409,
          "journal_limit",
          "Журнал заполнен; тренировки синхронизируются отдельно",
        )
      newEntries.forEach { entry ->
        jdbc.update(
          "INSERT INTO coach_journal(user_id,id,workout_id,device_id,created_at,payload) VALUES (?,?,?,?,?,?::jsonb)",
          user,
          entry.id,
          entry.workoutId,
          entry.deviceId,
          entry.createdAt,
          json.writeValueAsString(entry.payload),
        )
      }
      head.minSyncVersion = 3
      mapOf("accepted" to request.entries.size)
    }!!
  }

  fun page(user: UUID, after: Long, cursor: String?, limit: Int, version: String?): CoachPage {
    requireVersion(version)
    if (after < 0 || limit !in 1..100) bad("Некорректная пагинация")
    return tx.execute {
      postgres.ensureHead(user)
      heads.readLock(user)
      val current =
        jdbc.queryForObject(
          "SELECT COALESCE(max(sequence),0) FROM coach_journal WHERE user_id=? AND NOT deleted",
          Long::class.java,
          user,
        )!!
      val parts = cursor?.split(":")
      if (parts != null && (parts.size != 2 || parts.any { it.toLongOrNull() == null }))
        bad("Некорректный курсор")
      val position = parts?.get(0)?.toLong() ?: after
      val high = parts?.get(1)?.toLong() ?: current
      if (position < after || high < position || high > current) bad("Некорректный курсор")
      val rows =
        jdbc.query(
          "SELECT j.* FROM coach_journal j JOIN records r ON r.user_id=j.user_id AND r.kind='workout' AND r.id=j.workout_id AND NOT r.deleted WHERE j.user_id=? AND NOT j.deleted AND j.sequence>? AND j.sequence<=? ORDER BY j.sequence LIMIT ?",
          { rs, _ ->
            rs.getLong("sequence") to
              CoachEntry(
                rs.getObject("id", UUID::class.java),
                rs.getObject("workout_id", UUID::class.java),
                rs.getObject("device_id", UUID::class.java),
                rs.getLong("created_at"),
                json.readTree(rs.getString("payload")),
              )
          },
          user,
          position,
          high,
          limit + 1,
        )
      // A byte bound as well as an entry bound prevents one large history page starving sync.
      var bytes = 0
      val selected =
        rows.take(limit).takeWhile { (_, entry) ->
          bytes += json.writeValueAsBytes(entry).size
          bytes <= 512 * 1024
        }
      CoachPage(
        selected.map { it.second },
        if (selected.size < rows.size) "${selected.last().first}:$high" else null,
        high,
      )
    }!!
  }

  private fun requireVersion(version: String?) {
    if (version != "3")
      throw ApiException(426, "client_update_required", "Обновите приложение для Live Coach")
  }
}
