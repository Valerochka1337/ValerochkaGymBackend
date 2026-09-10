package tech.valerochkagym.repository.data

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.*
import org.springframework.stereotype.Repository
import tech.valerochkagym.repository.model.HeadEntity
import tech.valerochkagym.repository.model.OperationEntity
import tech.valerochkagym.repository.model.OperationId
import tech.valerochkagym.repository.model.RecordEntity
import tech.valerochkagym.repository.model.RecordId

interface HeadRepository : JpaRepository<HeadEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select h from HeadEntity h where h.userId = :user")
  fun writeLockOrNull(user: UUID): HeadEntity?

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select h from HeadEntity h where h.userId = :user")
  fun writeLock(user: UUID): HeadEntity

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select h from HeadEntity h where h.userId = :user")
  fun readLock(user: UUID): HeadEntity
}

interface RecordRepository : JpaRepository<RecordEntity, RecordId> {
  fun findByIdIn(ids: Collection<UUID>): List<RecordEntity>

  @Query("select count(r)>0 from RecordEntity r where r.id=:id") fun uuidExists(id: UUID): Boolean

  fun findByUserIdOrderByKindAscIdAsc(user: UUID): List<RecordEntity>

  fun findByUserIdAndKindAndIdInAndDeletedFalse(
    userId: UUID,
    kind: String,
    ids: Collection<UUID>,
  ): List<RecordEntity>

  @Query(
    value =
      "SELECT EXISTS(SELECT 1 FROM records WHERE user_id=:user AND kind='workout' AND NOT deleted AND (payload->'finishedAt' IS NULL OR payload->'finishedAt' = 'null'::jsonb))",
    nativeQuery = true,
  )
  fun hasActiveWorkout(user: UUID): Boolean
}

interface OperationRepository : JpaRepository<OperationEntity, OperationId>

@Repository
class PostgresSyncRepository(private val em: EntityManager) {
  fun ensureHead(user: UUID) {
    em
      .createNativeQuery("INSERT INTO sync_heads(user_id) VALUES (:user) ON CONFLICT DO NOTHING")
      .setParameter("user", user)
      .executeUpdate()
  }
}
