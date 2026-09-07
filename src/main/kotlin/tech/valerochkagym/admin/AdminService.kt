package tech.valerochkagym.admin

import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.auth.*
import tech.valerochkagym.data.*
import tech.valerochkagym.web.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class AdminEdit(
  val operationId: UUID,
  val baseRevision: Long,
  val payload: JsonNode,
  val reason: String,
)

data class AdminAction(val operationId: UUID, val reason: String)

data class AdminPage(val items: List<Map<String, Any?>>, val hasMore: Boolean, val offset: Int)

@Service
class AdminService(
  private val db: JdbcTemplate,
  private val tx: TransactionTemplate,
  private val auth: AuthService,
  private val crypto: Crypto,
  private val sync: SyncService,
  private val json: ObjectMapper,
  private val clock: Clock,
) {
  fun requireAdmin(id: UUID) {
    if (
      db
        .queryForList(
          "SELECT id FROM users WHERE id=? AND is_admin AND email_verified FOR SHARE",
          id,
        )
        .isEmpty()
    )
      throw ApiException(403, "admin_required", "Этот аккаунт не имеет доступа к админке")
  }

  fun openSession(tokens: Tokens): String {
    val identity = auth.authenticate(tokens.accessToken) ?: unauthorized()
    try {
      requireAdmin(identity.userId)
    } catch (e: ApiException) {
      auth.logout(identity)
      throw e
    }
    val token = crypto.token()
    db.update(
      "INSERT INTO admin_sessions(token_hash,session_id,expires_at) VALUES (?,?,?)",
      crypto.hash(token),
      identity.sessionId,
      Timestamp.from(clock.instant().plusSeconds(28800)),
    )
    db.update("DELETE FROM admin_sessions WHERE expires_at < ?", Timestamp.from(clock.instant()))
    return token
  }

  fun authenticate(token: String): Identity {
    if (token.length !in 40..100) unauthorized()
    val row =
      db
        .queryForList(
          """SELECT s.id,s.user_id,u.email FROM admin_sessions a
      JOIN sessions s ON s.id=a.session_id JOIN users u ON u.id=s.user_id
      WHERE a.token_hash=? AND a.expires_at>? AND s.revoked_at IS NULL
      AND s.refresh_expires_at>? AND u.is_admin AND u.email_verified""",
          crypto.hash(token),
          Timestamp.from(clock.instant()),
          Timestamp.from(clock.instant()),
        )
        .firstOrNull() ?: unauthorized()
    return Identity(row["user_id"] as UUID, row["id"] as UUID, row["email"] as String)
  }

  fun csrf(token: String) = crypto.hash("admin-csrf:" + token)

  fun summary(): Map<String, Any?> =
    mapOf(
      "users" to db.queryForObject("SELECT count(*) FROM users", Long::class.java),
      "verifiedUsers" to
        db.queryForObject("SELECT count(*) FROM users WHERE email_verified", Long::class.java),
      "activeSessions" to
        db.queryForObject(
          "SELECT count(*) FROM sessions WHERE revoked_at IS NULL AND refresh_expires_at>?",
          Long::class.java,
          Timestamp.from(clock.instant()),
        ),
      "records" to
        db.queryForList(
          "SELECT kind,count(*) AS count FROM records WHERE NOT deleted GROUP BY kind ORDER BY kind"
        ),
      "recentActions" to audit(null, 0, 5).items,
    )

  private fun page(offset: Int, limit: Int, query: String, args: List<Any>): AdminPage {
    if (offset !in 0..1_000_000 || limit !in 1..100) bad("Некорректная страница")
    val rows =
      db.queryForList(
        query + " LIMIT ? OFFSET ?",
        *(args + listOf(limit + 1, offset)).toTypedArray(),
      )
    return AdminPage(rows.take(limit), rows.size > limit, offset)
  }

  fun users(q: String, offset: Int, limit: Int): AdminPage {
    if (q.length > 200) bad("Слишком длинный поиск")
    return page(
      offset,
      limit,
      """SELECT u.id,u.email,u.email_verified,u.is_admin,u.created_at,
      (u.google_subject IS NOT NULL) AS google_connected,(u.password_hash IS NOT NULL) AS password_enabled,
      (SELECT count(*) FROM records r WHERE r.user_id=u.id AND NOT r.deleted) AS record_count
      FROM users u WHERE strpos(lower(u.email),lower(?))>0 OR u.id::text=? ORDER BY u.created_at DESC,u.id""",
      listOf(q, q),
    )
  }

  fun user(id: UUID): Map<String, Any?> {
    val row =
      db
        .queryForList(
          """SELECT id,email,email_verified,is_admin,created_at,
      (google_subject IS NOT NULL) AS google_connected,(password_hash IS NOT NULL) AS password_enabled
      FROM users WHERE id=?""",
          id,
        )
        .firstOrNull() ?: throw ApiException(404, "not_found", "Пользователь не найден")
    return row +
      mapOf(
        "counts" to
          db.queryForList(
            "SELECT kind,deleted,count(*) AS count FROM records WHERE user_id=? GROUP BY kind,deleted ORDER BY kind",
            id,
          ),
        "sessions" to auth.sessions(Identity(id, UUID(0, 0), row["email"] as String)),
      )
  }

  fun records(
    kind: String,
    user: UUID?,
    q: String,
    deleted: Boolean,
    offset: Int,
    limit: Int,
  ): AdminPage {
    if (kind !in RecordValidator.kinds || q.length > 200) bad("Некорректный фильтр")
    val args = mutableListOf<Any>(kind, deleted)
    val owner = if (user == null) "" else " AND r.user_id=?".also { args.add(user) }
    args.add(q)
    args.add(q)
    args.add(q)
    return page(
      offset,
      limit,
      """SELECT r.user_id,u.email,r.kind,r.id,r.revision,r.deleted,
      r.payload->>'name' AS name,COALESCE(r.payload->>'measuredAt',r.payload->>'startedAt',r.payload->>'dateTimeMillis') AS event_time,
      r.payload->>'muscleGroup' AS muscle_group,r.payload->>'type' AS exercise_type
      FROM records r JOIN users u ON u.id=r.user_id WHERE r.kind=? AND r.deleted=?""" +
        owner +
        " AND (strpos(lower(COALESCE(r.payload->>'name','')),lower(?))>0 OR r.id::text=? OR strpos(lower(u.email),lower(?))>0) ORDER BY u.email,r.id",
      args,
    )
  }

  fun exerciseOptions(user: UUID): List<Map<String, Any?>> =
    db.queryForList(
      "SELECT id,payload->>'name' AS name FROM records WHERE user_id=? AND kind='exercise' AND NOT deleted ORDER BY payload->>'name',id",
      user,
    )

  fun record(user: UUID, kind: String, id: UUID): Map<String, Any?> {
    if (kind !in RecordValidator.kinds) bad("Неизвестный тип")
    val row =
      db
        .queryForList(
          """SELECT r.user_id,u.email,r.kind,r.id,r.revision,r.deleted,r.payload::text
      FROM records r JOIN users u ON u.id=r.user_id WHERE r.user_id=? AND r.kind=? AND r.id=?""",
          user,
          kind,
          id,
        )
        .firstOrNull() ?: throw ApiException(404, "not_found", "Запись не найдена")
    return row + mapOf("payload" to (row["payload"] as String?)?.let(json::readTree))
  }

  private fun reason(value: String) {
    if (value.trim().length !in 3..500) bad("Укажите причину изменения: от 3 до 500 символов")
  }

  private fun previous(actor: Identity, operation: UUID, hash: String): Map<String, Any?>? {
    val row =
      db
        .queryForList(
          "SELECT request_hash,revision FROM admin_audit WHERE actor_id=? AND operation_id=?",
          actor.userId,
          operation,
        )
        .firstOrNull()
    if (row != null && row["request_hash"] != hash)
      throw ApiException(409, "operation_reused", "Идентификатор операции уже использован")
    return row
  }

  fun edit(actor: Identity, owner: UUID, kind: String, id: UUID, body: AdminEdit): PushResult {
    if (kind !in setOf("gym", "exercise")) bad("Редактирование этого типа не поддерживается")
    reason(body.reason)
    val hash = crypto.hash(json.writeValueAsString(listOf(owner, kind, id, body)))
    return tx.execute {
      requireAdmin(actor.userId)
      // Serialize duplicate admin operations before locking the target account's sync head.
      db.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        actor.userId.toString(),
      )
      previous(actor, body.operationId, hash)?.let {
        return@execute PushResult(it["revision"] as Long)
      }
      if (db.queryForList("SELECT id FROM users WHERE id=?", owner).isEmpty())
        throw ApiException(404, "not_found", "Пользователь не найден")
      db.update("INSERT INTO sync_heads(user_id) VALUES (?) ON CONFLICT DO NOTHING", owner)
      db.queryForList("SELECT revision FROM sync_heads WHERE user_id=? FOR UPDATE", owner)
      val before =
        db
          .queryForList(
            "SELECT payload::text,deleted FROM records WHERE user_id=? AND kind=? AND id=?",
            owner,
            kind,
            id,
          )
          .firstOrNull()
      if (before?.get("deleted") == true) bad("Удалённую запись нельзя изменить")
      val result =
        sync.push(
          owner,
          PushRequest(
            body.operationId,
            listOf(Change(kind, id, body.baseRevision, payload = body.payload)),
          ),
        )
      db.update(
        """INSERT INTO admin_audit(actor_id,actor_email,user_id,action,kind,record_id,operation_id,request_hash,reason,before_payload,after_payload,revision)
        VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)""",
        actor.userId,
        actor.email,
        owner,
        if (before == null) "create" else "edit",
        kind,
        id,
        body.operationId,
        hash,
        body.reason.trim(),
        before?.get("payload"),
        json.writeValueAsString(body.payload),
        result.revision,
      )
      result
    }!!
  }

  fun revoke(actor: Identity, owner: UUID, body: AdminAction) {
    reason(body.reason)
    if (owner == actor.userId) bad("Для своего аккаунта используйте кнопку выхода")
    val hash = crypto.hash(json.writeValueAsString(listOf("revoke_sessions", owner, body)))
    tx.executeWithoutResult {
      requireAdmin(actor.userId)
      db.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        actor.userId.toString(),
      )
      if (previous(actor, body.operationId, hash) != null) return@executeWithoutResult
      val target = user(owner)
      auth.logoutAll(Identity(owner, UUID(0, 0), target["email"] as String))
      db.update(
        """INSERT INTO admin_audit(actor_id,actor_email,user_id,action,operation_id,request_hash,reason)
        VALUES (?,?,?,'revoke_sessions',?,?,?)""",
        actor.userId,
        actor.email,
        owner,
        body.operationId,
        hash,
        body.reason.trim(),
      )
    }
  }

  fun audit(user: UUID?, offset: Int, limit: Int): AdminPage =
    page(
      offset,
      limit,
      "SELECT id,actor_email,user_id,action,kind,record_id,reason,revision,created_at FROM admin_audit" +
        (if (user == null) "" else " WHERE user_id=?") +
        " ORDER BY id DESC",
      if (user == null) emptyList() else listOf(user),
    )

  fun auditEntry(id: Long): Map<String, Any?> {
    val row =
      db
        .queryForList(
          "SELECT id,actor_email,user_id,action,kind,record_id,reason,revision,created_at,before_payload::text,after_payload::text FROM admin_audit WHERE id=?",
          id,
        )
        .firstOrNull() ?: throw ApiException(404, "not_found", "Изменение не найдено")
    return row +
      mapOf(
        "before_payload" to (row["before_payload"] as String?)?.let(json::readTree),
        "after_payload" to (row["after_payload"] as String?)?.let(json::readTree),
      )
  }
}
