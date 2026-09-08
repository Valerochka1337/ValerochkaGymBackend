package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "rate_limits")
class RateEntity(
  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var bucket: String = "",
  var windowStart: Instant = Instant.EPOCH,
  var count: Int = 0,
)
