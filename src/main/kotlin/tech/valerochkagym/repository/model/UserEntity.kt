package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity(
  @Id var id: UUID = UUID.randomUUID(),
  @Column(length = 254) var email: String = "",
  @Column(columnDefinition = "text") var passwordHash: String? = null,
  var emailVerified: Boolean = false,
  @Column(length = 255) var googleSubject: String? = null,
  var createdAt: Instant = Instant.now(),
  var isAdmin: Boolean = false,
)
