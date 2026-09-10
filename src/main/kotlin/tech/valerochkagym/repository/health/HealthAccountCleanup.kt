package tech.valerochkagym.repository.health

import java.util.UUID
import org.springframework.stereotype.Repository

@Repository
class HealthAccountCleanup(private val rows: HealthLedgerRepositories) {
  fun preflight(owner: UUID) {
    rows.lock(owner)
  }

  fun revalidate(identity: tech.valerochkagym.service.model.Identity) {
    rows.session(identity)
  }

  fun removeLocked(owner: UUID) {
    for (table in
      listOf(
        "health_operations",
        "health_disclosure_operations",
        "health_ai_disclosures",
        "health_heads",
        "health_head_history",
        "health_versions",
        "health_events",
        "health_logicals",
        "health_owner_state",
      )) rows.jdbc.update("DELETE FROM $table WHERE owner_id=?", owner)
  }
}
