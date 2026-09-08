package tech.valerochkagym.repository.admin

import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import tech.valerochkagym.repository.model.AuditEntity

interface AuditRepository : JpaRepository<AuditEntity, Long> {
  fun findByActorIdAndOperationId(actor: UUID, operation: UUID): AuditEntity?
}

@Repository
class AdminReadRepository(private val em: EntityManager) {
  private fun rows(
    query: String,
    args: Map<String, Any?> = emptyMap(),
    offset: Int = 0,
    limit: Int = 101,
  ): List<Map<String, Any?>> {
    val q = em.createQuery(query, Tuple::class.java)
    args.forEach { (k, v) -> q.setParameter(k, v) }
    return q.setFirstResult(offset).setMaxResults(limit).resultList.map { row ->
      row.elements.associate { it.alias to row.get(it) }
    }
  }

  private val userFields =
    "u.id as id,u.email as email,u.emailVerified as email_verified,u.isAdmin as is_admin,u.createdAt as created_at,(u.googleSubject is not null) as google_connected,(u.passwordHash is not null) as password_enabled"

  fun users(q: String, offset: Int, limit: Int) =
    rows(
      "select $userFields, (select count(r) from RecordEntity r where r.userId=u.id and not r.deleted) as record_count from UserEntity u where locate(lower(:q),lower(u.email))>0 or cast(u.id as string)=:q order by u.createdAt desc,u.id",
      mapOf("q" to q),
      offset,
      limit,
    )

  fun user(id: UUID) =
    rows("select $userFields from UserEntity u where u.id=:id", mapOf("id" to id)).firstOrNull()

  fun counts(user: UUID) =
    rows(
      "select r.kind as kind,r.deleted as deleted,count(r) as count from RecordEntity r where r.userId=:user group by r.kind,r.deleted order by r.kind",
      mapOf("user" to user),
    )

  fun summary(now: Instant): Map<String, Any?> =
    mapOf(
      "users" to em.createQuery("select count(u) from UserEntity u", Long::class.java).singleResult,
      "verifiedUsers" to
        em
          .createQuery("select count(u) from UserEntity u where u.emailVerified", Long::class.java)
          .singleResult,
      "activeSessions" to
        em
          .createQuery(
            "select count(s) from SessionEntity s where s.revokedAt is null and s.refreshExpiresAt>:now",
            Long::class.java,
          )
          .setParameter("now", now)
          .singleResult,
      "records" to
        rows(
          "select r.kind as kind,count(r) as count from RecordEntity r where not r.deleted group by r.kind order by r.kind"
        ),
    )

  private fun field(key: String) =
    "cast(function('jsonb_extract_path_text',r.payload,'$key') as string)"

  fun records(
    kind: String,
    user: UUID?,
    q: String,
    deleted: Boolean,
    offset: Int,
    limit: Int,
  ): List<Map<String, Any?>> {
    val name = field("name")
    return rows(
      """select r.userId as user_id,u.email as email,r.kind as kind,r.id as id,r.revision as revision,r.deleted as deleted,
      $name as name,coalesce(${field("measuredAt")},${field("startedAt")},${field("dateTimeMillis")}) as event_time,
      ${field("muscleGroup")} as muscle_group,${field("type")} as exercise_type
      from RecordEntity r join UserEntity u on u.id=r.userId where r.kind=:kind and r.deleted=:deleted
      ${if(user == null) "" else "and r.userId=:user"}
      and (locate(lower(:q),lower(coalesce($name,'')))>0 or cast(r.id as string)=:q or locate(lower(:q),lower(u.email))>0) order by u.email,r.id""",
      mapOf("kind" to kind, "deleted" to deleted, "q" to q) +
        if (user == null) emptyMap() else mapOf("user" to user),
      offset,
      limit,
    )
  }

  fun record(user: UUID, kind: String, id: UUID) =
    rows(
        "select r.userId as user_id,u.email as email,r.kind as kind,r.id as id,r.revision as revision,r.deleted as deleted,r.payload as payload from RecordEntity r join UserEntity u on u.id=r.userId where r.userId=:user and r.kind=:kind and r.id=:id",
        mapOf("user" to user, "kind" to kind, "id" to id),
      )
      .firstOrNull()

  fun exerciseOptions(user: UUID) =
    rows(
      "select r.id as id,${field("name")} as name from RecordEntity r where r.userId=:user and r.kind='exercise' and not r.deleted order by ${field("name")},r.id",
      mapOf("user" to user),
      limit = 20000,
    )

  private val auditFields =
    "a.id as id,a.actorEmail as actor_email,a.userId as user_id,a.action as action,a.kind as kind,a.recordId as record_id,a.reason as reason,a.revision as revision,a.createdAt as created_at"

  fun audit(user: UUID?, offset: Int, limit: Int) =
    rows(
      "select $auditFields from AuditEntity a ${if(user == null) "" else "where a.userId=:user"} order by a.id desc",
      if (user == null) emptyMap() else mapOf("user" to user),
      offset,
      limit,
    )

  fun auditEntry(id: Long) =
    rows(
        "select $auditFields,a.beforePayload as before_payload,a.afterPayload as after_payload from AuditEntity a where a.id=:id",
        mapOf("id" to id),
      )
      .firstOrNull()
}
