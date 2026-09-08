package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.io.Serializable
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

data class ChallengeId(var email: String = "", var purpose: String = "") : Serializable

@Entity
@Table(name = "email_challenges")
@IdClass(ChallengeId::class)
class ChallengeEntity(
  @Id @Column(length = 254) var email: String = "",
  @Id @Column(length = 16) var purpose: String = "",
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var codeHash: String = "",
  var expiresAt: Instant = Instant.EPOCH,
  var attempts: Int = 0,
)
