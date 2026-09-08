package tech.valerochkagym.repository.auth

import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.*
import org.springframework.transaction.annotation.Transactional
import tech.valerochkagym.repository.model.AdminSessionEntity
import tech.valerochkagym.repository.model.ChallengeEntity
import tech.valerochkagym.repository.model.ChallengeId
import tech.valerochkagym.repository.model.CredentialEntity
import tech.valerochkagym.repository.model.NonceEntity
import tech.valerochkagym.repository.model.RefreshEntity
import tech.valerochkagym.repository.model.SessionEntity
import tech.valerochkagym.repository.model.UserEntity

interface UserRepository : JpaRepository<UserEntity, UUID> {
  fun findByEmail(email: String): UserEntity?

  fun findByGoogleSubject(subject: String): UserEntity?

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from UserEntity u where u.email = :email")
  fun lockEmail(email: String): UserEntity?

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from UserEntity u where u.id = :id")
  fun lock(id: UUID): UserEntity?

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select u from UserEntity u where u.id = :id")
  fun readLock(id: UUID): UserEntity?

  @Modifying @Query("delete from UserEntity u where u.id = :id") fun remove(id: UUID): Int
}

interface SessionRepository : JpaRepository<SessionEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from SessionEntity s where s.id = :id")
  fun lock(id: UUID): SessionEntity?

  fun findByAccessHash(hash: String): SessionEntity?

  fun findByUserIdAndRevokedAtIsNullAndRefreshExpiresAtAfterOrderByCreatedAtDesc(
    userId: UUID,
    now: Instant,
  ): List<SessionEntity>

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
    "update SessionEntity s set s.revokedAt = :now where s.userId = :user and (:id is null or s.id = :id)"
  )
  fun revoke(user: UUID, id: UUID?, now: Instant): Int

  @Modifying
  @Query("delete from SessionEntity s where s.refreshExpiresAt < :now")
  fun cleanup(now: Instant): Int
}

interface RefreshRepository : JpaRepository<RefreshEntity, String> {
  @Query("select r.sessionId from RefreshEntity r where r.tokenHash = :hash")
  fun sessionId(hash: String): UUID?

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RefreshEntity r where r.tokenHash = :hash")
  fun lock(hash: String): RefreshEntity?
}

interface ChallengeRepository : JpaRepository<ChallengeEntity, ChallengeId> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ChallengeEntity c where c.email = :email and c.purpose = :purpose")
  fun lock(email: String, purpose: String): ChallengeEntity?

  @Modifying
  @Query("delete from ChallengeEntity c where c.email = :email")
  fun removeEmail(email: String): Int

  @Modifying
  @Query("delete from ChallengeEntity c where c.expiresAt < :now")
  fun cleanup(now: Instant): Int
}

interface NonceRepository : JpaRepository<NonceEntity, String> {
  @Transactional
  @Modifying
  @Query("delete from NonceEntity n where n.nonceHash = :hash and n.expiresAt > :now")
  fun consume(hash: String, now: Instant): Int

  @Modifying
  @Query("delete from NonceEntity n where n.expiresAt < :now")
  fun cleanup(now: Instant): Int
}

interface CredentialRepository : JpaRepository<CredentialEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE) fun findByUsername(username: String): CredentialEntity?
}

interface AdminSessionRepository : JpaRepository<AdminSessionEntity, String> {
  @Modifying
  @Query("delete from AdminSessionEntity s where s.expiresAt < :now")
  fun cleanup(now: Instant): Int
}
