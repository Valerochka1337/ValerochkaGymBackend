package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.io.Serializable
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

data class OperationId(var userId: UUID = UUID(0, 0), var operationId: UUID = UUID(0, 0)) :
  Serializable

@Entity
@Table(name = "sync_operations")
@IdClass(OperationId::class)
class OperationEntity(
  @Id var userId: UUID = UUID(0, 0),
  @Id var operationId: UUID = UUID(0, 0),
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var requestHash: String = "",
  var revision: Long = 0,
  var createdAt: Instant = Instant.now(),
)
