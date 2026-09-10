package tech.valerochkagym.service.coachrelation

import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.repository.coachrelation.CoachRelationRepositories
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.model.*
import tech.valerochkagym.service.health.HealthRawBodyReader
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class CoachRelationsService(
  private val rows: CoachRelationRepositories,
  private val heads: HeadRepository,
  private val crypto: CoachRelationCrypto,
  private val reader: HealthRawBodyReader,
  private val json: ObjectMapper,
  private val tx: TransactionTemplate,
  private val clock: Clock,
) {
  fun create(identity: Identity, raw: ByteArray): Any {
    val body = body(raw, setOf("operationId"))
    val operation = uuid(body, "operationId")
    return tx.execute {
      rows.guards(identity.userId)
      rows.session(identity)
      replay(
          identity,
          operation,
          "CREATE_INVITE",
          "POST /v1/coach-relations/invitations",
          identity.userId.toString(),
          raw,
        )
        ?.let { fail(409, "invite_token_not_replayable") }
      val id = UUID.randomUUID()
      val token = crypto.token()
      val expires = clock.instant().plusSeconds(604800)
      rows.jdbc.update(
        "INSERT INTO coach_relation_invitations(id,coach_id,live_coach_id,key_version,digest,expires_at) VALUES (?,?,?,?,?,?)",
        id,
        identity.userId,
        identity.userId,
        crypto.current,
        crypto.hmac("invite-token", crypto.current, token),
        Timestamp.from(expires),
      )
      save(
        identity,
        operation,
        "CREATE_INVITE",
        "POST /v1/coach-relations/invitations",
        identity.userId.toString(),
        raw,
        mapOf("inviteId" to id, "expiresAtMillis" to expires.toEpochMilli()),
        true,
      )
      CoachInvitationCreated(id, token, expires.toEpochMilli())
    }!!
  }

  fun accept(identity: Identity, raw: ByteArray): Any {
    val body = body(raw, setOf("operationId", "token", "calendar", "completedWorkouts"))
    val operation = uuid(body, "operationId")
    val token = body["token"].takeIf { it.isString }?.asString() ?: bad("Некорректный токен")
    if (!crypto.validToken(token)) bad("Некорректный токен")
    val calendar = boolean(body, "calendar")
    val completed = boolean(body, "completedWorkouts")
    preliminaryBinding(
      identity,
      operation,
      "ACCEPT_INVITE",
      "POST /v1/coach-relations/invitations/accept",
      raw,
    )
    val match =
      crypto
        .versions()
        .mapNotNull { v ->
          val digest = crypto.hmac("invite-token", v, token)
          rows.invite(v, digest)?.let { Triple(v, digest, it) }
        }
        .singleOrNull() ?: fail(404, "invite_not_found")
    val known = match.third
    return tx.execute {
      rows.guards(known.coachId, identity.userId)
      heads.writeLockOrNull(identity.userId) ?: unauthorized()
      val invite = rows.invite(match.first, match.second, true) ?: fail(404, "invite_not_found")
      if (invite.coachId != known.coachId) fail(404, "invite_not_found")
      val relation = invite.relationId?.let { rows.relation(it, true) ?: hidden() }
      rows.session(identity)
      replay(
        identity,
        operation,
        "ACCEPT_INVITE",
        "POST /v1/coach-relations/invitations/accept",
        invite.id.toString(),
        raw,
      )
      if (invite.coachId == identity.userId) bad("Нельзя принять своё приглашение")
      if (invite.liveCoachId == null) fail(404, "invite_not_found")
      if (invite.consumerId != null) {
        if (invite.consumerId != identity.userId) fail(409, "invite_used")
        val active = live(relation ?: hidden())
        if (active.calendar != calendar || active.completedWorkouts != completed)
          fail(409, "invite_consent_conflict")
        val result = response(active, identity.userId)
        save(
          identity,
          operation,
          "ACCEPT_INVITE",
          "POST /v1/coach-relations/invitations/accept",
          invite.id.toString(),
          raw,
          result,
          true,
        )
        return@execute result
      }
      if (!invite.expiresAt.isAfter(clock.instant())) fail(410, "invite_expired")
      if (
        rows.jdbc.queryForObject(
          "SELECT count(*) FROM coach_relations WHERE coach_id=? AND recipient_id=? AND state='ACTIVE'",
          Long::class.java,
          invite.coachId,
          identity.userId,
        )!! > 0
      )
        fail(409, "relation_exists")
      val id = UUID.randomUUID()
      val now = Timestamp.from(clock.instant())
      rows.jdbc.update(
        "INSERT INTO coach_relations(id,coach_id,recipient_id,live_coach_id,live_recipient_id,state,calendar,completed_workouts,created_at) VALUES (?,?,?,?,?,'ACTIVE',?,?,?)",
        id,
        invite.coachId,
        identity.userId,
        invite.coachId,
        identity.userId,
        calendar,
        completed,
        now,
      )
      rows.jdbc.update(
        "UPDATE coach_relation_invitations SET consumed_by=?,live_consumed_by=?,relation_id=? WHERE id=?",
        identity.userId,
        identity.userId,
        id,
        invite.id,
      )
      rows.advance(invite.coachId, identity.userId)
      val result = response(rows.relation(id)!!, identity.userId)
      save(
        identity,
        operation,
        "ACCEPT_INVITE",
        "POST /v1/coach-relations/invitations/accept",
        invite.id.toString(),
        raw,
        result,
        true,
      )
      result
    }!!
  }

  fun revoke(identity: Identity, id: UUID, raw: ByteArray): Any {
    val body = body(raw, setOf("operationId"))
    val operation = uuid(body, "operationId")
    preliminaryBinding(
      identity,
      operation,
      "REVOKE_RELATION",
      "POST /v1/coach-relations/{relationId}/revoke",
      raw,
      id.toString(),
    )
    val known = rows.relation(id) ?: hidden()
    if (identity.userId !in listOf(known.coachId, known.recipientId)) hidden()
    return tx.execute {
      rows.guards(known.coachId, known.recipientId)
      heads.writeLockOrNull(known.recipientId)
      val r = rows.relation(id, true) ?: hidden()
      rows.session(identity)
      replay(
          identity,
          operation,
          "REVOKE_RELATION",
          "POST /v1/coach-relations/{relationId}/revoke",
          id.toString(),
          raw,
        )
        ?.let {
          return@execute it
        }
      live(r)
      rows.jdbc.update(
        "UPDATE coach_relations SET state='REVOKED',revoked_at=? WHERE id=?",
        Timestamp.from(clock.instant()),
        id,
      )
      rows.advance(r.coachId, r.recipientId)
      val result = response(rows.relation(id)!!, identity.userId)
      save(
        identity,
        operation,
        "REVOKE_RELATION",
        "POST /v1/coach-relations/{relationId}/revoke",
        id.toString(),
        raw,
        result,
        false,
      )
      result
    }!!
  }

  fun directory(identity: Identity, mode: String, limit: Int, cursor: String?): CoachDirectoryPage {
    if (mode !in setOf("clients", "coaches") || limit !in 1..50) bad("Некорректная страница")
    return tx.execute {
      rows.guards(identity.userId)
      rows.session(identity)
      val revision = rows.revision(identity.userId)
      val fields = pageCursor(cursor, identity.userId, mode, null, null, revision)
      val after =
        fields?.get("lastRelationId")?.takeIf(String::isNotEmpty)?.let(UUID::fromString)
          ?: UUID(0, 0)
      val column = if (mode == "clients") "coach_id" else "recipient_id"
      val ids =
        rows.jdbc.query(
          "SELECT id FROM coach_relations WHERE $column=? AND id>? ORDER BY id LIMIT ?",
          { rs, _ -> rs.getObject(1, UUID::class.java) },
          identity.userId,
          after,
          limit + 1,
        )
      val emitted = ids.take(limit).map { response(rows.relation(it)!!, identity.userId) }
      CoachDirectoryPage(
        emitted,
        if (ids.size > limit)
          nextCursor(identity.userId, mode, null, null, revision, emitted.last().relationId, null)
        else null,
        revision,
      )
    }!!
  }

  fun projection(
    identity: Identity,
    id: UUID,
    mode: String,
    limit: Int,
    cursor: String?,
  ): CoachProjectionPage {
    if (mode !in setOf("calendar", "completed-workouts") || limit !in 1..50)
      bad("Некорректная страница")
    val known = rows.relation(id) ?: hidden()
    return tx.execute {
      rows.guards(known.coachId, known.recipientId)
      val head = heads.writeLockOrNull(known.recipientId) ?: hidden()
      val r = live(rows.relation(id, true) ?: hidden())
      if (
        r.coachId != identity.userId ||
          (mode == "calendar" && !r.calendar) ||
          (mode == "completed-workouts" && !r.completedWorkouts)
      )
        hidden()
      rows.session(identity)
      val fields = pageCursor(cursor, identity.userId, mode, id, r.recipientId, head.revision)
      val after =
        fields?.get("lastRecordId")?.takeIf(String::isNotEmpty)?.let(UUID::fromString) ?: UUID(0, 0)
      val kind = if (mode == "calendar") "calendar_plan" else "workout"
      val extra =
        if (mode == "completed-workouts") " AND payload->>'finishedAt' IS NOT NULL" else ""
      val candidates =
        rows.jdbc.query(
          "SELECT id,payload::text FROM records WHERE user_id=? AND kind=? AND NOT deleted AND id>?$extra ORDER BY id LIMIT ?",
          { rs, _ -> rs.getObject(1, UUID::class.java) to json.readTree(rs.getString(2)) },
          r.recipientId,
          kind,
          after,
          limit + 1,
        )
      val emitted = mutableListOf<Map<String, Any?>>()
      var last: UUID? = null
      for ((recordId, payload) in candidates.take(limit)) {
        val item =
          if (mode == "calendar") calendar(r.recipientId, recordId, payload)
          else completed(r.recipientId, recordId, payload)
        val tentative =
          CoachProjectionPage(
            emitted + item,
            nextCursor(identity.userId, mode, id, r.recipientId, head.revision, null, recordId),
            head.revision,
          )
        if (json.writeValueAsBytes(tentative).size > 1048576) {
          if (emitted.isEmpty()) fail(413, "payload_too_large")
          break
        }
        emitted += item
        last = recordId
      }
      CoachProjectionPage(
        emitted,
        if (candidates.size > emitted.size && last != null)
          nextCursor(identity.userId, mode, id, r.recipientId, head.revision, null, last)
        else null,
        head.revision,
      )
    }!!
  }

  private fun record(owner: UUID, kind: String, id: String): JsonNode =
    rows.jdbc
      .query(
        "SELECT payload::text FROM records WHERE user_id=? AND kind=? AND id=? AND NOT deleted",
        { rs, _ -> json.readTree(rs.getString(1)) },
        owner,
        kind,
        UUID.fromString(id),
      )
      .singleOrNull()
      ?: if (kind == "exercise")
        rows.jdbc
          .query(
            "SELECT payload::text FROM standard_records WHERE kind='exercise' AND id=?",
            { rs, _ -> json.readTree(rs.getString(1)) },
            UUID.fromString(id),
          )
          .singleOrNull() ?: hidden()
      else hidden()

  private fun calendar(owner: UUID, id: UUID, p: JsonNode): Map<String, Any?> {
    val routine = record(owner, "routine", p["routineId"].asString())
    return mapOf(
      "calendarPlanId" to id,
      "routineId" to p["routineId"].asString(),
      "startsAtMillis" to p["startsAtMillis"].asLong(),
      "timeZoneId" to p["timeZoneId"].asString(),
      "title" to routine["name"].asString(),
      "exercises" to
        routine["exercises"].toList().map { e ->
          val exercise = record(owner, "exercise", e["exerciseId"].asString())
          mapOf(
            "name" to exercise["name"].asString(),
            "type" to exercise["type"].asString(),
            "plannedSetCount" to e["plannedSets"].size(),
          )
        },
    )
  }

  private fun completed(owner: UUID, id: UUID, p: JsonNode): Map<String, Any?> =
    mapOf(
      "workoutId" to id,
      "finishedAtMillis" to p["finishedAt"].asLong(),
      "exercises" to
        p["exercises"].toList().map { e ->
          mapOf(
            "exerciseId" to e["exerciseId"].asString(),
            "name" to record(owner, "exercise", e["exerciseId"].asString())["name"].asString(),
            "sets" to
              e["sets"]
                .toList()
                .filter { it["isCompleted"]?.asBoolean() == true }
                .map { set ->
                  listOf("weightKg", "reps", "durationSec", "speedKmh", "inclinePct")
                    .associateWith { field ->
                      val actual = "actual" + field.replaceFirstChar(Char::uppercase)
                      (if (set.has(actual)) set[actual] else set[field])?.takeUnless(
                        JsonNode::isNull
                      )
                    }
                },
          )
        },
    )

  private fun pageCursor(
    raw: String?,
    actor: UUID,
    kind: String,
    relation: UUID?,
    recipient: UUID?,
    revision: Long,
  ): Map<String, String>? {
    if (raw == null) return null
    val p = crypto.decode(raw)
    if (
      p["actor"] != actor.toString() ||
        p["kind"] != kind ||
        p["relation"] != relation?.toString().orEmpty() ||
        p["recipient"] != recipient?.toString().orEmpty()
    )
      fail(400, "invalid_cursor")
    if (
      p[if (relation == null) "directoryRevision" else "recipientSyncRevision"] !=
        revision.toString()
    )
      fail(409, "relation_snapshot_changed")
    return p
  }

  private fun nextCursor(
    actor: UUID,
    kind: String,
    relation: UUID?,
    recipient: UUID?,
    revision: Long,
    lastRelation: UUID?,
    lastRecord: UUID?,
  ) =
    crypto.cursor(
      linkedMapOf(
        "kind" to kind,
        "actor" to actor.toString(),
        "relation" to relation?.toString().orEmpty(),
        "recipient" to recipient?.toString().orEmpty(),
        "directoryRevision" to if (relation == null) revision.toString() else "",
        "recipientSyncRevision" to if (relation != null) revision.toString() else "",
        "lastRelationId" to lastRelation?.toString().orEmpty(),
        "lastRecordId" to lastRecord?.toString().orEmpty(),
        "expiresAtMillis" to (clock.millis() + 900000).toString(),
      )
    )

  /**
   * Read-only early conflict detection; transaction repeats binding validation under lifecycle
   * guards.
   */
  fun preliminaryBinding(
    identity: Identity,
    operation: UUID,
    action: String,
    route: String,
    raw: ByteArray,
    tuple: String? = null,
  ) {
    val existing =
      rows.jdbc
        .query(
          "SELECT action,normalized_route,resource_tuple,raw_sha256 FROM coach_relation_operations WHERE actor_id=? AND operation_id=?",
          { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) },
          identity.userId,
          operation,
        )
        .singleOrNull() ?: return
    if (
      existing[0] != action ||
        existing[1] != route ||
        existing[3] != reader.sha256(raw) ||
        (tuple != null && existing[2] != tuple)
    )
      fail(409, "relation_operation_conflict")
  }

  fun replay(
    identity: Identity,
    operation: UUID,
    action: String,
    route: String,
    tuple: String,
    raw: ByteArray,
  ): JsonNode? {
    val existing =
      rows.jdbc
        .query(
          "SELECT action,normalized_route,resource_tuple,raw_sha256,result::text FROM coach_relation_operations WHERE actor_id=? AND operation_id=?",
          { rs, _ ->
            listOf(
              rs.getString(1),
              rs.getString(2),
              rs.getString(3),
              rs.getString(4),
              rs.getString(5),
            )
          },
          identity.userId,
          operation,
        )
        .singleOrNull() ?: return null
    if (existing.take(4) != listOf(action, route, tuple, reader.sha256(raw)))
      fail(409, "relation_operation_conflict")
    return json.readTree(existing[4])
  }

  fun save(
    identity: Identity,
    operation: UUID,
    action: String,
    route: String,
    tuple: String,
    raw: ByteArray,
    result: Any,
    secret: Boolean = false,
  ) {
    rows.jdbc.update(
      "INSERT INTO coach_relation_operations(actor_id,operation_id,action,normalized_route,resource_tuple,raw_sha256,raw_request,result,created_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT(actor_id,operation_id) DO NOTHING",
      identity.userId,
      operation,
      action,
      route,
      tuple,
      reader.sha256(raw),
      if (secret) null else raw,
      json.writeValueAsString(result),
      Timestamp.from(clock.instant()),
    )
  }

  fun body(raw: ByteArray, keys: Set<String>): JsonNode =
    reader.tree(raw).also {
      if (!it.isObject || it.propertyNames().toSet() != keys) bad("Некорректные поля запроса")
    }

  fun uuid(body: JsonNode, key: String): UUID =
    try {
      val s = body[key].asString()
      UUID.fromString(s).also {
        if (it.toString() != s || it.version() !in 1..5 || it.variant() != 2)
          bad("Некорректный UUID")
      }
    } catch (_: Exception) {
      bad("Некорректный UUID")
    }

  private fun boolean(body: JsonNode, key: String) =
    body[key].takeIf(JsonNode::isBoolean)?.asBoolean() ?: bad("Требуется явное согласие")

  fun live(r: CoachRelationRow) =
    r.also {
      if (it.state != "ACTIVE" || it.liveCoachId == null || it.liveRecipientId == null) hidden()
    }

  private fun response(r: CoachRelationRow, actor: UUID) =
    CoachRelationResponse(
      r.id,
      if (actor == r.coachId) r.recipientId else r.coachId,
      r.state,
      r.calendar,
      r.completedWorkouts,
      r.createdAt.toEpochMilli(),
      r.revokedAt?.toEpochMilli(),
    )

  private fun hidden(): Nothing = fail(404, "relation_not_found")

  private fun fail(status: Int, code: String): Nothing =
    throw ApiException(status, code, "Связь недоступна")
}
