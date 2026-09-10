package tech.valerochkagym.service.health

import java.time.Clock
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.repository.health.HealthLedgerRepositories
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.ObjectMapper

@Service
class HealthAiDisclosureService(
  private val rows: HealthLedgerRepositories,
  private val tx: TransactionTemplate,
  private val json: ObjectMapper,
  private val raw: HealthRawBodyReader,
  private val validator: HealthLedgerValidator,
  private val ledger: HealthLedgerService,
  private val clock: Clock,
  @Value("\${gym.health.max-bytes:209715200}") private val maxBytes: Long,
) {
  fun receipt(owner: UUID): Map<String, Any> {
    val r =
      rows.jdbc
        .queryForList("SELECT * FROM health_ai_disclosures WHERE owner_id=?", owner)
        .singleOrNull()
    return linkedMapOf(
      "revision" to ((r?.get("revision") as Number?)?.toLong() ?: 0L),
      "noticeVersion" to ((r?.get("notice_version") as Number?)?.toInt() ?: 0),
      "enabled" to (r?.get("enabled") ?: false),
      "recordedAtEpochMs" to ((r?.get("recorded_at") as Number?)?.toLong() ?: 0L),
    )
  }

  fun read(identity: Identity): Map<String, Any> =
    tx.execute {
      rows.lock(identity.userId)
      rows.session(identity)
      receipt(identity.userId)
    }!!

  fun mutate(identity: Identity, bytes: ByteArray): ByteArray {
    val hash = raw.sha256(bytes)
    val n = validator.disclosure(raw.tree(bytes))
    return tx.execute {
      val owner = identity.userId
      val state = rows.lock(owner) ?: rows.lock(owner, true) ?: unauthorized()
      rows.session(identity)
      val id = UUID.fromString(n["operationId"].asString())
      ledger
        .replay(owner, id, hash, "health_disclosure_operations", "consent_operation_reused")
        ?.let {
          return@execute it
        }
      val current = receipt(owner)
      if (current["revision"] != n["baseRevision"].asLong())
        throw ApiException(409, "consent_revision_conflict", "Согласие изменилось")
      val revision = n["baseRevision"].asLong() + 1
      val notice = n["noticeVersion"].asInt()
      val enabled = n["enabled"].asBoolean()
      val time = clock.millis()
      val result =
        json.writeValueAsBytes(
          linkedMapOf(
            "revision" to revision,
            "noticeVersion" to notice,
            "enabled" to enabled,
            "recordedAtEpochMs" to time,
          )
        )
      val used = (state["stored_bytes"] as Number).toLong()
      val cost = bytes.size.toLong() + result.size
      if (cost > maxBytes - used)
        throw ApiException(409, "health_account_limit", "Превышен объём медицинских записей")
      rows.jdbc.update(
        "INSERT INTO health_ai_disclosures VALUES(?,?,?,?,?) ON CONFLICT(owner_id) DO UPDATE SET revision=EXCLUDED.revision,notice_version=EXCLUDED.notice_version,enabled=EXCLUDED.enabled,recorded_at=EXCLUDED.recorded_at",
        owner,
        revision,
        notice,
        enabled,
        time,
      )
      rows.jdbc.update(
        "INSERT INTO health_disclosure_operations VALUES(?,?,?,?,?)",
        owner,
        id,
        hash,
        bytes,
        result,
      )
      rows.jdbc.update(
        "UPDATE health_owner_state SET stored_bytes=? WHERE owner_id=?",
        used + cost,
        owner,
      )
      result
    }!!
  }

  fun requireEnabled(identity: Identity, expected: Long?) {
    tx.executeWithoutResult {
      rows.lock(identity.userId)
      rows.session(identity)
      val r = receipt(identity.userId)
      if (
        expected == null ||
          expected <= 0 ||
          r["revision"] != expected ||
          r["enabled"] != true ||
          r["noticeVersion"] != 1
      )
        throw ApiException(403, "health_ai_consent_required", "Подтвердите передачу фото для AI")
    }
  }
}
