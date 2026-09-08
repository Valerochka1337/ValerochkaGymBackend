package tech.valerochkagym.service.admin

import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.controller.model.AdminAction
import tech.valerochkagym.controller.model.AdminEdit
import tech.valerochkagym.controller.model.AdminPage
import tech.valerochkagym.controller.model.Change
import tech.valerochkagym.controller.model.PushRequest
import tech.valerochkagym.controller.model.PushResult
import tech.valerochkagym.repository.admin.AdminReadRepository
import tech.valerochkagym.repository.admin.AuditRepository
import tech.valerochkagym.repository.auth.AdminSessionRepository
import tech.valerochkagym.repository.auth.PostgresAuthRepository
import tech.valerochkagym.repository.auth.SessionRepository
import tech.valerochkagym.repository.auth.UserRepository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.data.PostgresSyncRepository
import tech.valerochkagym.repository.data.RecordRepository
import tech.valerochkagym.repository.model.AdminSessionEntity
import tech.valerochkagym.repository.model.AuditEntity
import tech.valerochkagym.repository.model.RecordId
import tech.valerochkagym.service.auth.AuthService
import tech.valerochkagym.service.data.RecordValidator
import tech.valerochkagym.service.data.SyncService
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.ObjectMapper

@Service
class AdminService(
  private val standard: tech.valerochkagym.repository.catalog.StandardRepository,
  private val catalog: tech.valerochkagym.repository.catalog.CatalogStateRepository,
  private val users: UserRepository,
  private val sessions: SessionRepository,
  private val browsers: AdminSessionRepository,
  private val postgres: PostgresAuthRepository,
  private val syncPostgres: PostgresSyncRepository,
  private val heads: HeadRepository,
  private val records: RecordRepository,
  private val audits: AuditRepository,
  private val read: AdminReadRepository,
  private val tx: TransactionTemplate,
  private val auth: AuthService,
  private val crypto: Crypto,
  private val sync: SyncService,
  private val json: ObjectMapper,
  private val clock: Clock,
) {
  fun requireAdmin(id: UUID) {
    val user = users.readLock(id)
    if (user == null || !user.isAdmin || !user.emailVerified)
      throw ApiException(403, "admin_required", "Этот аккаунт не имеет доступа к админке")
  }

  fun login(username: String, password: String): Pair<String, String> =
    tx.execute {
      val tokens = auth.loginAdmin(username, password)
      val identity = auth.authenticate(tokens.accessToken) ?: unauthorized()
      requireAdmin(identity.userId)
      val token = crypto.token()
      browsers.save(
        AdminSessionEntity(
          crypto.hash(token),
          identity.sessionId,
          clock.instant().plusSeconds(28800),
        )
      )
      browsers.cleanup(clock.instant())
      tokens.email to token
    }!!

  fun authenticate(token: String): Identity {
    if (token.length !in 40..100) unauthorized()
    val browser = browsers.findById(crypto.hash(token)).orElse(null) ?: unauthorized()
    val session = sessions.findById(browser.sessionId).orElse(null) ?: unauthorized()
    val user = users.findById(session.userId).orElse(null) ?: unauthorized()
    if (
      !browser.expiresAt.isAfter(clock.instant()) ||
        session.revokedAt != null ||
        !session.refreshExpiresAt.isAfter(clock.instant()) ||
        !user.isAdmin ||
        !user.emailVerified
    )
      unauthorized()
    return Identity(user.id, session.id, user.email)
  }

  fun csrf(token: String) = crypto.hash("admin-csrf:" + token)

  fun summary() = read.summary(clock.instant()) + mapOf("recentActions" to audit(null, 0, 5).items)

  private fun page(offset: Int, limit: Int, load: () -> List<Map<String, Any?>>): AdminPage {
    if (offset !in 0..1_000_000 || limit !in 1..100) bad("Некорректная страница")
    val rows = load()
    return AdminPage(rows.take(limit), rows.size > limit, offset)
  }

  fun users(q: String, offset: Int, limit: Int): AdminPage {
    if (q.length > 200) bad("Слишком длинный поиск")
    return page(offset, limit) { read.users(q, offset, limit + 1) }
  }

  fun user(id: UUID): Map<String, Any?> {
    val row = read.user(id) ?: throw ApiException(404, "not_found", "Пользователь не найден")
    return row +
      mapOf(
        "counts" to read.counts(id),
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
    return page(offset, limit) { read.records(kind, user, q, deleted, offset, limit + 1) }
  }

  fun exerciseOptions(user: UUID) =
    read.exerciseOptions(user) +
      standard
        .findAllByOrderByKindAscIdAsc()
        .filter { it.kind == "exercise" && !it.archived }
        .map {
          mapOf(
            "id" to it.id,
            "name" to json.readTree(it.payload)["name"].asString(),
            "origin" to "STANDARD",
          )
        }

  fun record(user: UUID, kind: String, id: UUID): Map<String, Any?> {
    if (kind !in RecordValidator.kinds) bad("Неизвестный тип")
    val row =
      read.record(user, kind, id) ?: throw ApiException(404, "not_found", "Запись не найдена")
    return row + mapOf("payload" to (row["payload"] as String?)?.let(json::readTree))
  }

  private fun reason(value: String) {
    if (value.trim().length !in 3..500) bad("Укажите причину изменения: от 3 до 500 символов")
  }

  private fun previous(actor: Identity, operation: UUID, hash: String): AuditEntity? {
    val row = audits.findByActorIdAndOperationId(actor.userId, operation)
    if (row != null && row.requestHash != hash)
      throw ApiException(409, "operation_reused", "Идентификатор операции уже использован")
    return row
  }

  fun edit(actor: Identity, owner: UUID, kind: String, id: UUID, body: AdminEdit): PushResult {
    if (kind !in setOf("gym", "exercise")) bad("Редактирование этого типа не поддерживается")
    reason(body.reason)
    val hash = crypto.hash(json.writeValueAsString(listOf(owner, kind, id, body)))
    return tx.execute {
      requireAdmin(actor.userId)
      postgres.lockActor(actor.userId)
      previous(actor, body.operationId, hash)?.let {
        return@execute PushResult(it.revision!!)
      }
      if (!users.existsById(owner)) throw ApiException(404, "not_found", "Пользователь не найден")
      val catalogRevision = catalog.readLock().revision
      syncPostgres.ensureHead(owner)
      heads.writeLock(owner)
      val before = records.findById(RecordId(owner, kind, id)).orElse(null)
      if (before?.deleted == true) bad("Удалённую запись нельзя изменить")
      val beforePayload = before?.payload
      val result =
        sync.push(
          owner,
          PushRequest(
            body.operationId,
            listOf(Change(kind, id, body.baseRevision, payload = body.payload)),
            catalogRevision,
          ),
        )
      audits.save(
        AuditEntity(
          actorId = actor.userId,
          actorEmail = actor.email,
          userId = owner,
          action = if (before == null) "create" else "edit",
          kind = kind,
          recordId = id,
          operationId = body.operationId,
          requestHash = hash,
          reason = body.reason.trim(),
          beforePayload = beforePayload,
          afterPayload = json.writeValueAsString(body.payload),
          revision = result.revision,
        )
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
      postgres.lockActor(actor.userId)
      if (previous(actor, body.operationId, hash) != null) return@executeWithoutResult
      val target = user(owner)
      auth.logoutAll(Identity(owner, UUID(0, 0), target["email"] as String))
      audits.save(
        AuditEntity(
          actorId = actor.userId,
          actorEmail = actor.email,
          userId = owner,
          action = "revoke_sessions",
          operationId = body.operationId,
          requestHash = hash,
          reason = body.reason.trim(),
        )
      )
    }
  }

  fun audit(user: UUID?, offset: Int, limit: Int) =
    page(offset, limit) { read.audit(user, offset, limit + 1) }

  fun auditEntry(id: Long): Map<String, Any?> {
    val row = read.auditEntry(id) ?: throw ApiException(404, "not_found", "Изменение не найдено")
    return row +
      mapOf(
        "before_payload" to (row["before_payload"] as String?)?.let(json::readTree),
        "after_payload" to (row["after_payload"] as String?)?.let(json::readTree),
      )
  }
}
