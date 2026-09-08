package tech.valerochkagym.repository.auth

import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/** PostgreSQL atomic operations are confined to this persistence adapter. */
@Repository
@Transactional
class PostgresAuthRepository(private val em: EntityManager) {
  fun createUser(id: UUID, email: String, hash: String) {
    em
      .createNativeQuery(
        "INSERT INTO users(id,email,password_hash) VALUES (:id,:email,:hash) ON CONFLICT(email) DO NOTHING"
      )
      .setParameter("id", id)
      .setParameter("email", email)
      .setParameter("hash", hash)
      .executeUpdate()
  }

  fun lockIdentityCreation() {
    em.createNativeQuery("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE").executeUpdate()
  }

  fun lockActor(id: UUID) {
    em
      .createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:id,0))")
      .setParameter("id", id.toString())
      .resultList
  }

  fun rate(hash: String): Int =
    (em
        .createNativeQuery(
          """INSERT INTO rate_limits(bucket,window_start,count) VALUES (:hash,date_trunc('minute',now()),1)
    ON CONFLICT(bucket) DO UPDATE SET window_start=date_trunc('minute',now()),
    count=CASE WHEN rate_limits.window_start=date_trunc('minute',now()) THEN rate_limits.count+1 ELSE 1 END RETURNING count""",
          Int::class.java,
        )
        .setParameter("hash", hash)
        .singleResult as Number)
      .toInt()

  fun cleanup(now: Instant) {
    em
      .createQuery("delete from RateEntity r where r.windowStart < :now")
      .setParameter("now", now)
      .executeUpdate()
  }
}
