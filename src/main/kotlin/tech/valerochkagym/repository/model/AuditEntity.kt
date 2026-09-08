package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "admin_audit")
class AuditEntity(
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
  var actorId: UUID = UUID(0, 0),
  @Column(length = 254) var actorEmail: String = "",
  var userId: UUID? = null,
  @Column(length = 40) var action: String = "",
  @Column(length = 32) var kind: String? = null,
  var recordId: UUID? = null,
  var operationId: UUID = UUID(0, 0),
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var requestHash: String = "",
  @Column(length = 500) var reason: String = "",
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  var beforePayload: String? = null,
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var afterPayload: String? = null,
  var revision: Long? = null,
  var createdAt: Instant = Instant.now(),
)
