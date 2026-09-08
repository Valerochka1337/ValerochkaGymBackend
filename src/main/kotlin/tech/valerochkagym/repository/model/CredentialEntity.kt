package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "admin_credentials")
class CredentialEntity(
  @Id var userId: UUID = UUID(0, 0),
  @Column(length = 64) var username: String = "",
  @Column(length = 255) var passwordHash: String = "",
)
