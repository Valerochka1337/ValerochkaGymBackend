package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "google_nonces")
class NonceEntity(
  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var nonceHash: String = "",
  var expiresAt: Instant = Instant.EPOCH,
)
