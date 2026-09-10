package tech.valerochkagym.repository.coachrelation

import java.time.Clock
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.repository.auth.SessionRepository
import tech.valerochkagym.repository.model.*
import tech.valerochkagym.service.model.Identity

@Repository
class CoachRelationRepositories(
  val jdbc: JdbcTemplate,
  private val sessions: SessionRepository,
  private val clock: Clock,
) {
  /** Outer guard: always before catalog/head/relation/user/session, also on account deletion. */
  fun guards(vararg actors: UUID) {
    actors.distinct().sortedBy(UUID::toString).forEach { actor ->
      jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 23))",
        { _, _ -> Unit },
        "coach-lifecycle:$actor",
      )
    }
  }

  fun session(identity: Identity) {
    if (
      jdbc
        .query(
          "SELECT id FROM users WHERE id=? FOR UPDATE",
          { rs, _ -> rs.getObject(1, UUID::class.java) },
          identity.userId,
        )
        .isEmpty()
    )
      unauthorized()
    val s = sessions.lock(identity.sessionId) ?: unauthorized()
    if (
      s.userId != identity.userId ||
        s.revokedAt != null ||
        !s.accessExpiresAt.isAfter(clock.instant()) ||
        !s.refreshExpiresAt.isAfter(clock.instant())
    )
      unauthorized()
  }

  fun relation(id: UUID, lock: Boolean = false): CoachRelationRow? =
    jdbc
      .query(
        "SELECT * FROM coach_relations WHERE id=?" + if (lock) " FOR UPDATE" else "",
        { rs, _ ->
          CoachRelationRow(
            rs.getObject("id", UUID::class.java),
            rs.getObject("coach_id", UUID::class.java),
            rs.getObject("recipient_id", UUID::class.java),
            rs.getString("state"),
            rs.getBoolean("calendar"),
            rs.getBoolean("completed_workouts"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("revoked_at")?.toInstant(),
            rs.getObject("live_coach_id", UUID::class.java),
            rs.getObject("live_recipient_id", UUID::class.java),
          )
        },
        id,
      )
      .singleOrNull()

  fun invite(version: Int, digest: String, lock: Boolean = false): CoachInvitationRow? =
    jdbc
      .query(
        "SELECT * FROM coach_relation_invitations WHERE key_version=? AND digest=?" +
          if (lock) " FOR UPDATE" else "",
        { rs, _ ->
          CoachInvitationRow(
            rs.getObject("id", UUID::class.java),
            rs.getObject("coach_id", UUID::class.java),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getObject("consumed_by", UUID::class.java),
            rs.getObject("relation_id", UUID::class.java),
            rs.getObject("live_coach_id", UUID::class.java),
          )
        },
        version,
        digest,
      )
      .singleOrNull()

  fun advance(vararg actors: UUID) {
    actors.distinct().sortedBy(UUID::toString).forEach {
      jdbc.update(
        "INSERT INTO coach_relation_directory_heads(actor_id,revision) SELECT id,1 FROM users WHERE id=? ON CONFLICT(actor_id) DO UPDATE SET revision=coach_relation_directory_heads.revision+1",
        it,
      )
    }
  }

  fun revision(actor: UUID): Long =
    jdbc
      .query(
        "SELECT revision FROM coach_relation_directory_heads WHERE actor_id=? FOR SHARE",
        { rs, _ -> rs.getLong(1) },
        actor,
      )
      .singleOrNull() ?: 0
}
