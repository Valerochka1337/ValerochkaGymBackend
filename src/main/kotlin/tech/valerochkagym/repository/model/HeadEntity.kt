package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "sync_heads")
class HeadEntity(
  @Id var userId: UUID = UUID(0, 0),
  var revision: Long = 0,
  var minSyncVersion: Int = 2,
)
