package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.io.Serializable
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "catalog_state")
class CatalogStateEntity(
  @Id var id: Int = 1,
  var revision: Long = 0,
  var active: Boolean = false,
  var sourceUserId: UUID? = null,
  var activatedAt: Instant? = null,
)

data class StandardId(var kind: String = "", var id: UUID = UUID(0, 0)) : Serializable

@Entity
@Table(name = "standard_records")
@IdClass(StandardId::class)
class StandardEntity(
  @Id @Column(length = 32) var kind: String = "",
  @Id var id: UUID = UUID(0, 0),
  var revision: Long = 0,
  var archived: Boolean = false,
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var payload: String = "{}",
)

@Entity
@Table(name = "equipment")
class EquipmentEntity(
  @Id @Column(length = 100) var id: String = "",
  var revision: Long = 0,
  var archived: Boolean = false,
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var payload: String = "{}",
)
