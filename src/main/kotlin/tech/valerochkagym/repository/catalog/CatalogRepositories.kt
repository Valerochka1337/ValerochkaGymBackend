package tech.valerochkagym.repository.catalog

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.*
import tech.valerochkagym.repository.model.CatalogStateEntity
import tech.valerochkagym.repository.model.EquipmentEntity
import tech.valerochkagym.repository.model.StandardEntity
import tech.valerochkagym.repository.model.StandardId

interface CatalogStateRepository : JpaRepository<CatalogStateEntity, Int> {
  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select c from CatalogStateEntity c where c.id = 1")
  fun readLock(): CatalogStateEntity

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from CatalogStateEntity c where c.id = 1")
  fun writeLock(): CatalogStateEntity
}

interface StandardRepository : JpaRepository<StandardEntity, StandardId> {
  fun findAllByOrderByKindAscIdAsc(): List<StandardEntity>

  fun findByKindAndIdInAndArchivedFalse(
    kind: String,
    ids: Collection<java.util.UUID>,
  ): List<StandardEntity>
}

interface EquipmentRepository : JpaRepository<EquipmentEntity, String> {
  fun findAllByOrderByIdAsc(): List<EquipmentEntity>
}
