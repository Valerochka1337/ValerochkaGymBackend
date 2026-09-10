package tech.valerochkagym.repository.health

import java.time.Clock
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.repository.auth.SessionRepository
import tech.valerochkagym.service.model.Identity

@Repository
class HealthLedgerRepositories(
  val jdbc: JdbcTemplate,
  private val sessions: SessionRepository,
  private val clock: Clock,
) {
  fun lock(owner: UUID, create: Boolean = false): Map<String, Any?>? {
    // A transaction-scoped owner mutex also exists before the first persistent owner row.
    // Deletion takes this same mutex before users.lock, closing the first-write FK race.
    jdbc.queryForList("SELECT pg_advisory_xact_lock(18492417, hashtext(?))", owner.toString())
    if (create)
      jdbc.update(
        "INSERT INTO health_owner_state(owner_id) SELECT id FROM users WHERE id=? ON CONFLICT DO NOTHING",
        owner,
      )
    return jdbc
      .queryForList("SELECT * FROM health_owner_state WHERE owner_id=? FOR UPDATE", owner)
      .singleOrNull()
  }

  fun session(identity: Identity) {
    val s = sessions.lock(identity.sessionId) ?: unauthorized()
    val now = clock.instant()
    if (
      s.userId != identity.userId ||
        s.revokedAt != null ||
        !s.accessExpiresAt.isAfter(now) ||
        !s.refreshExpiresAt.isAfter(now)
    )
      unauthorized()
  }
}
