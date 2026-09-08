package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "sessions")
class SessionEntity(
  @Id var id: UUID = UUID.randomUUID(),
  var userId: UUID = UUID(0, 0),
  @Column(length = 100) var deviceName: String = "",
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var accessHash: String = "",
  var accessExpiresAt: Instant = Instant.EPOCH,
  var refreshExpiresAt: Instant = Instant.EPOCH,
  var createdAt: Instant = Instant.now(),
  var revokedAt: Instant? = null,
)
