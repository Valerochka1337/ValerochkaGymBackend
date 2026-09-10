package tech.valerochkagym.repository.ai

import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import tech.valerochkagym.repository.model.CalendarAiAttemptEntity
import tech.valerochkagym.repository.model.CalendarAiAttemptId

interface CalendarAiAttemptRepository :
  JpaRepository<CalendarAiAttemptEntity, CalendarAiAttemptId> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
    "select a from CalendarAiAttemptEntity a where a.ownerId=:ownerId and a.requestId=:requestId"
  )
  fun writeLock(ownerId: UUID, requestId: UUID): CalendarAiAttemptEntity?
}
