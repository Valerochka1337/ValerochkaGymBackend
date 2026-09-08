package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "refresh_tokens")
class RefreshEntity(
  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var tokenHash: String = "",
  var sessionId: UUID = UUID(0, 0),
  var usedAt: Instant? = null,
)
