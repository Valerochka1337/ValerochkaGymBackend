package tech.valerochkagym.service.health

import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.repository.health.HealthLedgerRepositories
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class HealthLedgerService(
  private val rows: HealthLedgerRepositories,
  private val tx: TransactionTemplate,
  private val validator: HealthLedgerValidator,
  private val rawReader: HealthRawBodyReader,
  private val json: ObjectMapper,
  private val cursors: HealthCursorCodec,
  @Value("\${gym.health.max-bytes:209715200}") private val maxBytes: Long,
  @Value("\${gym.health.max-versions:100000}") private val maxVersions: Long,
) {
  private val db
    get() = rows.jdbc

  fun operation(identity: Identity, raw: ByteArray): ByteArray {
    val hash = rawReader.sha256(raw)
    val n = validator.operation(rawReader.tree(raw))
    return tx.execute {
      val owner = identity.userId
      val state = rows.lock(owner) ?: rows.lock(owner, true) ?: unauthorized()
      rows.session(identity)
      val operation = UUID.fromString(n["operationId"].asString())
      replay(owner, operation, hash, "health_operations", "health_operation_reused")?.let {
        return@execute it
      }
      var sequence = (state["server_sequence"] as Number).toLong()
      var revision = (state["health_revision"] as Number).toLong()
      val existing = mutableMapOf<String, JsonNode>()
      val fingerprints = mutableMapOf<String, String>()
      fun stored(id: String): JsonNode? =
        existing[id]
          ?: db
            .queryForList(
              "SELECT body,server_sequence,health_revision,fingerprint FROM health_versions WHERE owner_id=? AND version_id=?",
              owner,
              UUID.fromString(id),
            )
            .singleOrNull()
            ?.let {
              val v = json.readTree(it["body"] as String) as tools.jackson.databind.node.ObjectNode
              v.put("serverSequence", (it["server_sequence"] as Number).toLong())
              v.put("healthRevision", (it["health_revision"] as Number).toLong())
              fingerprints[id] = it["fingerprint"] as String
              existing[id] = v
              v
            }
      val submitted = n["versions"].toList().map(validator::version)
      val fresh = mutableListOf<JsonNode>()
      val all = mutableMapOf<String, JsonNode>()
      // Classify every collision before references, including references in earlier new versions.
      for (v in submitted) {
        val id = v["versionId"].asString()
        val old = stored(id)
        if (old != null) {
          if (fingerprints.getValue(id) != rawReader.sha256(json.writeValueAsBytes(v)))
            conflict("health_version_collision")
          all[id] = old
        } else {
          fresh.add(v)
          all[id] = v
        }
      }
      val logicals = mutableMapOf<String, String>()
      for (v in fresh) {
        val logical = v["logicalId"].asString()
        val kind = v["kind"].asString()
        val parent = v["parentVersionId"]
        val currentKind =
          logicals[logical]
            ?: db
              .queryForList(
                "SELECT kind FROM health_logicals WHERE owner_id=? AND logical_id=?",
                owner,
                UUID.fromString(logical),
              )
              .singleOrNull()
              ?.get("kind") as String?
        if (parent.isNull) {
          if (currentKind != null || v["state"].asString() != "CONFIRMED") reference()
        } else {
          val previous = existing[parent.asString()] ?: stored(parent.asString()) ?: reference()
          if (
            previous["logicalId"].asString() != logical ||
              previous["kind"].asString() != kind ||
              currentKind != kind
          )
            reference()
        }
        logicals[logical] = kind
        existing[v["versionId"].asString()] = v
      }
      val selected = mutableMapOf<String, JsonNode?>()
      fun head(logical: String): JsonNode? {
        if (selected.containsKey(logical)) return selected[logical]
        val h =
          db
            .queryForList(
              "SELECT e.body FROM health_heads h JOIN health_head_history x USING(owner_id,logical_id,head_revision) JOIN health_events e USING(owner_id,health_revision) WHERE h.owner_id=? AND h.logical_id=?",
              owner,
              UUID.fromString(logical),
            )
            .singleOrNull()
            ?.let { json.readTree(it["body"] as String) }
        selected[logical] = h
        return h
      }
      val outcomes = mutableListOf<Pair<JsonNode, String>>()
      val changed = mutableListOf<JsonNode>()
      for (intent in n["heads"]) {
        val logical = intent["logicalId"].asString()
        val id = intent["currentVersionId"].asString()
        val target = all[id] ?: stored(id) ?: reference()
        if (target["logicalId"].asString() != logical) reference()
        val old = head(logical)
        val base = intent["baseHeadRevision"].asLong()
        if (old == null && base != 0L) reference()
        val outcome =
          if (old != null && old["currentVersionId"].asString() == id) "ALREADY_CURRENT"
          else if (old != null && old["headRevision"].asLong() != base) "STALE" else "APPLIED"
        if (outcome == "APPLIED") {
          val h =
            json.valueToTree<JsonNode>(
              linkedMapOf(
                "logicalId" to logical,
                "currentVersionId" to id,
                "headRevision" to ((old?.get("headRevision")?.asLong() ?: 0) + 1),
                "kind" to target["kind"].asString(),
                "deleted" to (target["state"].asString() == "TOMBSTONE"),
                "healthRevision" to 0,
              )
            )
          selected[logical] = h
          changed.add(h)
        }
        outcomes.add(intent to outcome)
      }
      fun liveReport(v: JsonNode) {
        if (v["kind"].asString() != "health_observation" || v["state"].asString() == "TOMBSTONE")
          return
        val report = head(v["payload"]["reportLogicalId"].asString()) ?: reference()
        if (report["kind"].asString() != "health_report" || report["deleted"].asBoolean())
          reference()
      }
      fresh.forEach(::liveReport)
      changed.forEach { h ->
        liveReport(
          all[h["currentVersionId"].asString()]
            ?: stored(h["currentVersionId"].asString())
            ?: reference()
        )
      }
      val versionEvents = mutableListOf<JsonNode>()
      for (v in fresh) {
        val record =
          json.readTree(json.writeValueAsBytes(v)) as tools.jackson.databind.node.ObjectNode
        record.put("serverSequence", ++sequence)
        record.put("healthRevision", ++revision)
        versionEvents.add(record)
        all[v["versionId"].asString()] = record
      }
      changed.forEach {
        (it as tools.jackson.databind.node.ObjectNode).put("healthRevision", ++revision)
      }
      val result =
        json.writeValueAsBytes(
          linkedMapOf(
            "operationId" to operation.toString(),
            "versionReceipts" to
              submitted.map { v ->
                val r = all.getValue(v["versionId"].asString())
                linkedMapOf(
                  "versionId" to r["versionId"].asString(),
                  "serverSequence" to r["serverSequence"].asLong(),
                  "healthRevision" to r["healthRevision"].asLong(),
                )
              },
            "headResults" to
              outcomes.map { (intent, outcome) ->
                val h = selected.getValue(intent["logicalId"].asString())!!
                linkedMapOf(
                  "logicalId" to intent["logicalId"].asString(),
                  "submittedCurrentVersionId" to intent["currentVersionId"].asString(),
                  "serverCurrentVersionId" to h["currentVersionId"].asString(),
                  "headRevision" to h["headRevision"].asLong(),
                  "healthRevision" to h["healthRevision"].asLong(),
                  "outcome" to outcome,
                )
              },
          )
        )
      val cost =
        raw.size.toLong() +
          result.size +
          versionEvents.sumOf { json.writeValueAsBytes(it).size.toLong() } +
          fresh.sumOf { json.writeValueAsBytes(it).size.toLong() } +
          changed.sumOf { json.writeValueAsBytes(it).size.toLong() }
      val used = (state["stored_bytes"] as Number).toLong()
      if (sequence > maxVersions || cost > maxBytes - used) conflict("health_account_limit")
      for (v in fresh) if (v["parentVersionId"].isNull)
        db.update(
          "INSERT INTO health_logicals(owner_id,logical_id,kind,created_at) VALUES(?,?,?,?)",
          owner,
          UUID.fromString(v["logicalId"].asString()),
          v["kind"].asString(),
          v["enteredAtEpochMs"].asLong(),
        )
      for ((index, v) in fresh.withIndex()) {
        val record = versionEvents[index]
        event(owner, record, "VERSION")
        db.update(
          "INSERT INTO health_versions(owner_id,version_id,logical_id,kind,parent_version_id,state,server_sequence,health_revision,body,fingerprint,report_logical_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          owner,
          UUID.fromString(v["versionId"].asString()),
          UUID.fromString(v["logicalId"].asString()),
          v["kind"].asString(),
          v["parentVersionId"].takeUnless { it.isNull }?.asString()?.let(UUID::fromString),
          v["state"].asString(),
          record["serverSequence"].asLong(),
          record["healthRevision"].asLong(),
          json.writeValueAsString(v),
          rawReader.sha256(json.writeValueAsBytes(v)),
          v["payload"].get("reportLogicalId")?.asString()?.let(UUID::fromString),
        )
      }
      for (h in changed) {
        event(owner, h, "HEAD")
        db.update(
          "INSERT INTO health_head_history(owner_id,logical_id,head_revision,current_version_id,kind,deleted,health_revision) VALUES(?,?,?,?,?,?,?)",
          owner,
          UUID.fromString(h["logicalId"].asString()),
          h["headRevision"].asLong(),
          UUID.fromString(h["currentVersionId"].asString()),
          h["kind"].asString(),
          h["deleted"].asBoolean(),
          h["healthRevision"].asLong(),
        )
        db.update(
          "INSERT INTO health_heads(owner_id,logical_id,head_revision) VALUES(?,?,?) ON CONFLICT(owner_id,logical_id) DO UPDATE SET head_revision=EXCLUDED.head_revision",
          owner,
          UUID.fromString(h["logicalId"].asString()),
          h["headRevision"].asLong(),
        )
      }
      db.update(
        "UPDATE health_owner_state SET server_sequence=?,health_revision=?,stored_bytes=? WHERE owner_id=?",
        sequence,
        revision,
        used + cost,
        owner,
      )
      db.update(
        "INSERT INTO health_operations VALUES(?,?,?,?,?)",
        owner,
        operation,
        hash,
        raw,
        result,
      )
      result
    }!!
  }

  private fun event(owner: UUID, n: JsonNode, type: String) {
    db.update(
      "INSERT INTO health_events VALUES(?,?,?,?)",
      owner,
      n["healthRevision"].asLong(),
      type,
      json.writeValueAsString(n),
    )
  }

  fun replay(owner: UUID, id: UUID, hash: String, table: String, code: String): ByteArray? {
    require(table in setOf("health_operations", "health_disclosure_operations"))
    val old =
      db
        .queryForList(
          "SELECT raw_hash,result_body FROM $table WHERE owner_id=? AND operation_id=?",
          owner,
          id,
        )
        .singleOrNull() ?: return null
    if (old["raw_hash"] != hash) conflict(code)
    return old["result_body"] as ByteArray
  }

  fun page(
    identity: Identity,
    mode: String,
    after: String?,
    pageToken: String?,
    limit: Int,
  ): ByteArray {
    if (limit !in 1..500) bad("Некорректный размер страницы")
    val high =
      tx.execute {
        val state = rows.lock(identity.userId)
        rows.session(identity)
        (state?.get("health_revision") as Number?)?.toLong() ?: 0L
      }!!
    val c =
      if (mode == "changes") cursors.decode(identity.userId, after ?: cursors.expired(), true).h
      else 0L
    val token =
      if (pageToken != null) cursors.decode(identity.userId, pageToken)
      else cursors.fresh(mode, c, high)
    if (token.mode != mode || token.c != c || token.h > high) cursors.expired()
    val snapshotClause =
      if (mode == "snapshot")
        "AND (e.event_type='VERSION' OR EXISTS(SELECT 1 FROM health_head_history h WHERE h.owner_id=e.owner_id AND h.health_revision=e.health_revision AND h.head_revision=(SELECT MAX(x.head_revision) FROM health_head_history x WHERE x.owner_id=h.owner_id AND x.logical_id=h.logical_id AND x.health_revision<=?)))"
      else ""
    val args = mutableListOf<Any>(identity.userId, maxOf(c, token.last), token.h)
    if (mode == "snapshot") args.add(token.h)
    args.add(limit + 1)
    val events =
      db.queryForList(
        "SELECT e.body,e.event_type,e.health_revision FROM health_events e WHERE e.owner_id=? AND e.health_revision>? AND e.health_revision<=? $snapshotClause ORDER BY e.health_revision LIMIT ?",
        *args.toTypedArray(),
      )
    val versions = mutableListOf<JsonNode>()
    val heads = mutableListOf<JsonNode>()
    var last = token.last
    var consumed = 0
    var bytes = 8192
    for (e in events.take(limit)) {
      val body = e["body"] as String
      val size = body.toByteArray(Charsets.UTF_8).size + 1
      if (bytes + size > 5242880) break
      bytes += size
      last = (e["health_revision"] as Number).toLong()
      consumed++
      (if (e["event_type"] == "VERSION") versions else heads).add(json.readTree(body))
    }
    val more = events.size > consumed
    if (more && consumed == 0)
      throw ApiException(413, "payload_too_large", "Запись превышает размер страницы")
    val result =
      json.writeValueAsBytes(
        linkedMapOf(
          "versions" to versions,
          "heads" to heads,
          "nextPageToken" to
            if (more) cursors.encode(identity.userId, token.copy(last = last)) else null,
          "commitCursor" to
            if (more) null
            else cursors.encode(identity.userId, token.copy(last = token.h, expires = 0), true),
        )
      )
    // The single immutable-event SQL statement has one MVCC snapshot. Deletion/revocation
    // during reading or serialization must discard its result before returning any bytes.
    tx.executeWithoutResult { rows.session(identity) }
    return result
  }

  private fun reference(): Nothing =
    throw ApiException(400, "health_reference_invalid", "Некорректная ссылка медицинской записи")

  private fun conflict(code: String): Nothing =
    throw ApiException(409, code, "Конфликт медицинских записей")
}
