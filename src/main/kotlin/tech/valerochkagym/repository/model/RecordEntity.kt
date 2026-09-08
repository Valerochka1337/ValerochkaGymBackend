package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.io.Serializable
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

data class RecordId(
  var userId: UUID = UUID(0, 0),
  var kind: String = "",
  var id: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "records")
@IdClass(RecordId::class)
class RecordEntity(
  @Id var userId: UUID = UUID(0, 0),
  @Id @Column(length = 32) var kind: String = "",
  @Id var id: UUID = UUID(0, 0),
  var revision: Long = 0,
  var deleted: Boolean = false,
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var payload: String? = null,
)
