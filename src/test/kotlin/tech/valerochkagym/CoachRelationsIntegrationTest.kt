package tech.valerochkagym

import java.net.URI
import java.net.http.*
import java.util.UUID
import java.util.concurrent.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.*
import org.testcontainers.postgresql.PostgreSQLContainer
import tech.valerochkagym.service.auth.AuthService
import tech.valerochkagym.service.coachrelation.*
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.*

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoachRelationsIntegrationTest {
  companion object {
    @Container
    @JvmStatic
    val postgres =
      PostgreSQLContainer(
        "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
      )

    @DynamicPropertySource
    @JvmStatic
    fun properties(r: DynamicPropertyRegistry) {
      r.add("spring.datasource.url", postgres::getJdbcUrl)
      r.add("spring.datasource.username", postgres::getUsername)
      r.add("spring.datasource.password", postgres::getPassword)
      r.add("gym.token-pepper") { "test-pepper-with-at-least-thirty-two-bytes" }
    }
  }

  @Autowired lateinit var db: JdbcTemplate
  @Autowired lateinit var json: ObjectMapper
  @Autowired lateinit var crypto: Crypto
  @Autowired lateinit var relationCrypto: CoachRelationCrypto
  @Autowired lateinit var auth: AuthService
  @Autowired lateinit var tx: TransactionTemplate
  @LocalServerPort var port = 0
  private val client = HttpClient.newHttpClient()

  data class Actor(val id: UUID, val session: UUID, val token: String) {
    fun identity() = Identity(id, session, "$id@example.com")
  }

  @BeforeEach
  fun reset() {
    db.execute("TRUNCATE users,standard_records CASCADE")
    db.update("UPDATE catalog_state SET revision=0,active=false")
  }

  private fun actor(): Actor {
    val id = UUID.randomUUID()
    val session = UUID.randomUUID()
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    db.update("INSERT INTO users(id,email,email_verified) VALUES (?,?,true)", id, "$id@example.com")
    db.update("INSERT INTO sync_heads(user_id,revision) VALUES (?,0)", id)
    db.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,'coach-test',?,TIMESTAMPTZ '2100-01-01',TIMESTAMPTZ '2100-01-01')",
      session,
      id,
      crypto.hash(token),
    )
    return Actor(id, session, token)
  }

  private fun call(
    a: Actor,
    path: String,
    body: String? = null,
    method: String = if (body == null) "GET" else "POST",
  ): HttpResponse<String> {
    val builder =
      HttpRequest.newBuilder(URI("http://localhost:$port/v1$path"))
        .header("Authorization", "Bearer ${a.token}")
        .header("Content-Type", "application/json")
        .header("X-Gym-Capabilities", "calendar-plans")
    return client.send(
      builder
        .method(
          method,
          body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody(),
        )
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )
  }

  private fun operation() = "{\"operationId\":\"${UUID.randomUUID()}\"}"

  private fun invite(a: Actor): String {
    val r = call(a, "/coach-relations/invitations", operation())
    assertEquals(201, r.statusCode(), r.body())
    return json.readTree(r.body())["token"].asString()
  }

  private fun consent(
    token: String,
    calendar: Boolean = true,
    completed: Boolean = true,
    op: UUID = UUID.randomUUID(),
  ) =
    json.writeValueAsString(
      mapOf(
        "operationId" to op,
        "token" to token,
        "calendar" to calendar,
        "completedWorkouts" to completed,
      )
    )

  private fun relation(
    coach: Actor,
    recipient: Actor,
    calendar: Boolean = true,
    completed: Boolean = true,
  ): UUID {
    val r =
      call(
        recipient,
        "/coach-relations/invitations/accept",
        consent(invite(coach), calendar, completed),
      )
    assertEquals(200, r.statusCode(), r.body())
    return UUID.fromString(json.readTree(r.body())["relationId"].asString())
  }

  private fun code(response: HttpResponse<String>, status: Int, code: String) {
    assertEquals(status, response.statusCode(), response.body())
    assertEquals(code, json.readTree(response.body())["code"].asString())
  }

  @Test
  fun `fixture hmac vectors and invitation secret ledger are exact`() {
    val fixture = json.readTree(javaClass.getResourceAsStream("/coach-relations-contract.json"))
    fixture["crypto"]["vectors"].forEach { v ->
      assertEquals(
        v["hmacHex"].asString(),
        relationCrypto.hmac(
          v["purpose"].asString(),
          1,
          (v["message"] ?: v["canonicalQuery"]).asString(),
        ),
      )
    }
    val coach = actor()
    val raw = operation()
    val created = call(coach, "/coach-relations/invitations", raw)
    assertEquals(201, created.statusCode(), created.body())
    code(call(coach, "/coach-relations/invitations", raw), 409, "invite_token_not_replayable")
    val recipient = actor()
    val accepted =
      call(
        recipient,
        "/coach-relations/invitations/accept",
        consent(json.readTree(created.body())["token"].asString()),
      )
    assertEquals(200, accepted.statusCode(), accepted.body())
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM coach_relation_operations WHERE raw_request IS NULL AND result::text NOT LIKE '%token%'",
        Int::class.java,
      ),
    )
  }

  @Test
  fun `consent replay conflicts expiry and revocation never regrant`() {
    val coach = actor()
    val recipient = actor()
    val other = actor()
    val token = invite(coach)
    val raw = consent(token)
    val first = call(recipient, "/coach-relations/invitations/accept", raw)
    assertEquals(200, first.statusCode(), first.body())
    assertEquals(first.body(), call(recipient, "/coach-relations/invitations/accept", raw).body())
    code(call(other, "/coach-relations/invitations/accept", consent(token)), 409, "invite_used")
    code(
      call(recipient, "/coach-relations/invitations/accept", consent(token, false)),
      409,
      "invite_consent_conflict",
    )
    val id = json.readTree(first.body())["relationId"].asString()
    val revoke = operation()
    assertEquals(200, call(coach, "/coach-relations/$id/revoke", revoke).statusCode())
    assertEquals(200, call(coach, "/coach-relations/$id/revoke", revoke).statusCode())
    code(call(recipient, "/coach-relations/invitations/accept", raw), 404, "relation_not_found")
    val expired = invite(coach)
    db.update(
      "UPDATE coach_relation_invitations SET expires_at=now()-interval '1 second' WHERE relation_id IS NULL"
    )
    code(
      call(recipient, "/coach-relations/invitations/accept", consent(expired)),
      410,
      "invite_expired",
    )
  }

  @Test
  fun `independent grants and directory cursor bind actor mode and revision`() {
    val coach = actor()
    val recipient = actor()
    val other = actor()
    val id = relation(coach, recipient, true, false)
    assertEquals(200, call(coach, "/coach-relations/$id/calendar").statusCode())
    code(call(coach, "/coach-relations/$id/completed-workouts"), 404, "relation_not_found")
    code(call(other, "/coach-relations/$id/calendar"), 404, "relation_not_found")
    relation(coach, other)
    val page = call(coach, "/coach-relations/clients?limit=1")
    assertEquals(200, page.statusCode(), page.body())
    val cursor = json.readTree(page.body())["nextCursor"].asString()
    code(call(other, "/coach-relations/clients?limit=1&cursor=$cursor"), 400, "invalid_cursor")
    call(recipient, "/coach-relations/$id/revoke", operation())
    code(
      call(coach, "/coach-relations/clients?limit=1&cursor=$cursor"),
      409,
      "relation_snapshot_changed",
    )
  }

  @Test
  fun `global operation ledger rejects cross action and resource substitution`() {
    val coach = actor()
    val recipient = actor()
    val id = relation(coach, recipient)
    val raw = operation()
    assertEquals(201, call(coach, "/coach-relations/invitations", raw).statusCode())
    code(call(coach, "/coach-relations/$id/revoke", raw), 409, "relation_operation_conflict")
    assertEquals(
      "ACTIVE",
      db.queryForObject("SELECT state FROM coach_relations WHERE id=?", String::class.java, id),
    )
  }

  @Test
  fun `raw mutators reject duplicate trailing bom and omitted consent`() {
    val a = actor()
    listOf(
        "",
        "{} {}",
        "\uFEFF{}",
        "{\"operationId\":\"${UUID.randomUUID()}\",\"operationId\":\"${UUID.randomUUID()}\"}",
      )
      .forEach { code(call(a, "/coach-relations/invitations", it), 400, "invalid_request") }
    code(call(a, "/coach-relations/invitations/accept", operation()), 400, "invalid_request")
  }

  @Test
  fun `parallel consumers produce one relation and one used invite`() {
    val coach = actor()
    val a = actor()
    val b = actor()
    val token = invite(coach)
    val pool = Executors.newFixedThreadPool(2)
    val start = CyclicBarrier(2)
    try {
      val futures =
        listOf(a, b).map { owner ->
          pool.submit<HttpResponse<String>> {
            start.await(5, TimeUnit.SECONDS)
            call(owner, "/coach-relations/invitations/accept", consent(token))
          }
        }
      assertEquals(
        listOf(200, 409),
        futures.map { it.get(10, TimeUnit.SECONDS).statusCode() }.sorted(),
      )
      assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_relations", Int::class.java))
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `confirmed deletion detaches relation invitations and preserves tombstone`() {
    val coach = actor()
    val recipient = actor()
    val id = relation(coach, recipient)
    val code = "12345678"
    db.update(
      "INSERT INTO email_challenges(email,purpose,code_hash,expires_at,attempts) VALUES (?,'delete',?,TIMESTAMPTZ '2100-01-01',0)",
      "${coach.id}@example.com",
      crypto.hash("${coach.id}@example.com:delete:$code"),
    )
    auth.delete(coach.identity(), code)
    assertEquals(
      "REVOKED",
      db.queryForObject("SELECT state FROM coach_relations WHERE id=?", String::class.java, id),
    )
    Assertions.assertNull(
      db.queryForObject(
        "SELECT live_coach_id FROM coach_relations WHERE id=?",
        UUID::class.java,
        id,
      )
    )
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM users WHERE id=?", Int::class.java, recipient.id),
    )
  }

  @Autowired
  lateinit var relationRows: tech.valerochkagym.repository.coachrelation.CoachRelationRepositories
  @Autowired lateinit var relationService: CoachRelationsService
  @Autowired
  lateinit var proposalAuthors:
    tech.valerochkagym.repository.trainingproposal.TrainingProposalAuthorSnapshots

  private fun exercise(): UUID {
    val id = UUID.randomUUID()
    db.update(
      "INSERT INTO standard_records(kind,id,revision,archived,payload) VALUES ('exercise',?,0,false,?::jsonb)",
      id,
      json.writeValueAsString(
        mapOf(
          "name" to "Присед",
          "type" to "STRENGTH",
          "muscleGroup" to "LEGS",
          "isCustom" to false,
          "updatedAt" to 1,
          "needsMuscleMapReview" to false,
          "equipmentRequirementState" to "KNOWN",
          "muscles" to emptyList<Any>(),
          "equipmentIds" to emptyList<Any>(),
        )
      ),
    )
    return id
  }

  private fun draft(exercise: UUID) =
    mapOf(
      "name" to "План",
      "gymIds" to emptyList<Any>(),
      "exercises" to
        listOf(
          mapOf(
            "exerciseId" to exercise,
            "restSeconds" to 60,
            "plannedSets" to
              listOf(
                mapOf(
                  "weightKg" to null,
                  "reps" to 10,
                  "durationSec" to null,
                  "speedKmh" to null,
                  "inclinePct" to null,
                )
              ),
          )
        ),
      "startsAtMillis" to 1893456000000,
      "timeZoneId" to "UTC",
    )

  private fun coachProposal(coach: Actor, relation: UUID, draft: Any): UUID {
    val raw =
      json.writeValueAsString(
        mapOf(
          "operationId" to UUID.randomUUID(),
          "expectedOwnerRevision" to 0,
          "expectedCatalogRevision" to 0,
          "draft" to draft,
        )
      )
    val r = call(coach, "/coach-relations/$relation/training-proposals", raw)
    assertEquals(201, r.statusCode(), r.body())
    assertEquals(
      json.readTree(r.body()),
      json.readTree(call(coach, "/coach-relations/$relation/training-proposals", raw).body()),
    )
    return UUID.fromString(json.readTree(r.body())["proposalId"].asString())
  }

  private fun approve(recipient: Actor, id: UUID, draft: Any, version: Int = 1) =
    call(
      recipient,
      "/training-proposals/$id/approve",
      json.writeValueAsString(
        mapOf("operationId" to UUID.randomUUID(), "version" to version, "draft" to draft)
      ),
    )

  @Test
  fun `coach create revise approve retains immutable origin and accepted replay after revoke`() {
    val coach = actor()
    val recipient = actor()
    val relation = relation(coach, recipient)
    val draft = draft(exercise())
    val proposal = coachProposal(coach, relation, draft)
    val revise =
      json.writeValueAsString(
        mapOf("operationId" to UUID.randomUUID(), "expectedVersion" to 1, "draft" to draft)
      )
    code(call(coach, "/training-proposals/$proposal/revoke", "{\"version\":1}"), 403, "forbidden")
    val revised =
      call(coach, "/coach-relations/$relation/training-proposals/$proposal", revise, "PUT")
    assertEquals(200, revised.statusCode(), revised.body())
    assertEquals(
      json.readTree(revised.body()),
      json.readTree(
        call(coach, "/coach-relations/$relation/training-proposals/$proposal", revise, "PUT").body()
      ),
    )
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM training_proposal_versions WHERE proposal_id=? AND origin_relation_id=?",
        Int::class.java,
        proposal,
        relation,
      ),
    )
    val approved = approve(recipient, proposal, draft, 2)
    assertEquals(200, approved.statusCode(), approved.body())
    call(coach, "/coach-relations/$relation/revoke", operation())
    assertEquals(200, approve(recipient, proposal, draft, 2).statusCode())
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        recipient.id,
      ),
    )
  }

  @Test
  fun `new same pair relation cannot authorize old pending proposal`() {
    val coach = actor()
    val recipient = actor()
    val old = relation(coach, recipient)
    val draft = draft(exercise())
    val proposal = coachProposal(coach, old, draft)
    call(recipient, "/coach-relations/$old/revoke", operation())
    val fresh = relation(coach, recipient)
    code(approve(recipient, proposal, draft), 403, "forbidden")
    code(
      call(
        coach,
        "/coach-relations/$fresh/training-proposals/$proposal",
        json.writeValueAsString(
          mapOf("operationId" to UUID.randomUUID(), "expectedVersion" to 1, "draft" to draft)
        ),
        "PUT",
      ),
      404,
      "proposal_not_found",
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM training_proposal_receipts", Int::class.java),
    )
  }

  private fun putRecord(owner: Actor, kind: String, id: UUID, payload: Any) {
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,?,?,0,false,?::jsonb) ON CONFLICT(user_id,kind,id) DO UPDATE SET payload=excluded.payload",
      owner.id,
      kind,
      id,
      json.writeValueAsString(payload),
    )
  }

  @Test
  fun `projection preserves empty large and zero actual facts without private fields`() {
    val coach = actor()
    val recipient = actor()
    val relation = relation(coach, recipient)
    val exercise = exercise()
    val workout = UUID.randomUUID()
    fun payload(count: Int, sets: Int) =
      mapOf(
        "finishedAt" to 100,
        "note" to "PRIVATE",
        "health" to "PRIVATE",
        "exercises" to
          List(count) {
            mapOf(
              "exerciseId" to exercise,
              "sets" to
                List(sets) {
                  mapOf(
                    "isCompleted" to true,
                    "weightKg" to 123,
                    "actualWeightKg" to null,
                    "reps" to 0,
                    "actualReps" to 0,
                    "durationSec" to 0,
                    "note" to "PRIVATE",
                    "targetWeightKg" to 999,
                  )
                },
            )
          },
      )
    for ((count, sets) in listOf(0 to 0, 1 to 0, 31 to 21, 200 to 1, 1 to 1000)) {
      putRecord(recipient, "workout", workout, payload(count, sets))
      val result = call(coach, "/coach-relations/$relation/completed-workouts")
      assertEquals(200, result.statusCode(), result.body())
      assertFalse(result.body().contains("PRIVATE"))
      assertFalse(result.body().contains("target"))
      val rows = json.readTree(result.body())["items"][0]["exercises"]
      assertEquals(count, rows.size())
      if (count > 0) {
        assertEquals(sets, rows[0]["sets"].size())
        if (sets > 0) {
          assertTrue(rows[0]["sets"][0]["weightKg"].isNull)
          assertEquals(0, rows[0]["sets"][0]["reps"].asInt())
        }
      }
    }
    putRecord(
      recipient,
      "workout",
      workout,
      mapOf(
        "finishedAt" to 100,
        "exercises" to
          listOf(
            mapOf(
              "exerciseId" to exercise,
              "sets" to
                listOf(
                  mapOf("isCompleted" to true, "weightKg" to 7),
                  mapOf("isCompleted" to false, "weightKg" to 999),
                ),
            )
          ),
      ),
    )
    val legacy =
      json.readTree(call(coach, "/coach-relations/$relation/completed-workouts").body())["items"][
        0]["exercises"][0]["sets"]
    assertEquals(1, legacy.size())
    assertEquals(7, legacy[0]["weightKg"].asInt())
    putRecord(recipient, "workout", workout, payload(200, 1000))
    code(call(coach, "/coach-relations/$relation/completed-workouts"), 413, "payload_too_large")
  }

  private fun deleteCode(actor: Actor): String {
    val code = "12345678"
    db.update(
      "INSERT INTO email_challenges(email,purpose,code_hash,expires_at,attempts) VALUES (?,'delete',?,TIMESTAMPTZ '2100-01-01',0)",
      "${actor.id}@example.com",
      crypto.hash("${actor.id}@example.com:delete:$code"),
    )
    return code
  }

  private fun waitingLock() {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    fun count() =
      db.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Int::class.java)!!
    while (count() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
    assertTrue(count() > 0, "request must reach PostgreSQL lock wait before release")
  }

  @Test
  fun `delete guard closes invitation consumption and first author binding before commit`() {
    for (bind in listOf(false, true)) {
      val coach = actor()
      val recipient = actor()
      val token = invite(coach)
      val code = deleteCode(coach)
      val locked = CountDownLatch(1)
      val release = CountDownLatch(1)
      val pool = Executors.newFixedThreadPool(2)
      try {
        val deletion =
          pool.submit {
            tx.executeWithoutResult {
              relationRows.guards(coach.id)
              locked.countDown()
              assertTrue(release.await(8, TimeUnit.SECONDS))
              auth.delete(coach.identity(), code)
            }
          }
        assertTrue(locked.await(5, TimeUnit.SECONDS))
        val pending =
          pool.submit<String> {
            if (bind) {
              try {
                proposalAuthors.bindAuthenticatedCoach(coach.identity())
                "bound"
              } catch (e: tech.valerochkagym.controller.advice.ApiException) {
                e.code
              }
            } else
              json
                .readTree(
                  call(recipient, "/coach-relations/invitations/accept", consent(token)).body()
                )["code"]
                .asString()
          }
        waitingLock()
        release.countDown()
        deletion.get(10, TimeUnit.SECONDS)
        assertEquals(
          if (bind) "unauthorized" else "invite_not_found",
          pending.get(10, TimeUnit.SECONDS),
        )
        assertEquals(
          0,
          db.queryForObject(
            "SELECT count(*) FROM coach_relations WHERE coach_id=?",
            Int::class.java,
            coach.id,
          ),
        )
        assertEquals(
          0,
          db.queryForObject(
            "SELECT count(*) FROM training_proposal_authors WHERE live_account_id=?",
            Int::class.java,
            coach.id,
          ),
        )
      } finally {
        release.countDown()
        pool.shutdownNow()
      }
    }
  }

  @Test
  fun `revoke barrier prevents pending approval from writing recipient records`() {
    val coach = actor()
    val recipient = actor()
    val relation = relation(coach, recipient)
    val draft = draft(exercise())
    val proposal = coachProposal(coach, relation, draft)
    val locked = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val revoking =
        pool.submit {
          tx.executeWithoutResult {
            relationRows.guards(coach.id, recipient.id)
            locked.countDown()
            assertTrue(release.await(8, TimeUnit.SECONDS))
            relationService.revoke(coach.identity(), relation, operation().toByteArray())
          }
        }
      assertTrue(locked.await(5, TimeUnit.SECONDS))
      val approving = pool.submit<HttpResponse<String>> { approve(recipient, proposal, draft) }
      waitingLock()
      release.countDown()
      revoking.get(10, TimeUnit.SECONDS)
      code(approving.get(10, TimeUnit.SECONDS), 403, "forbidden")
      assertEquals(
        0,
        db.queryForObject(
          "SELECT count(*) FROM training_proposal_receipts WHERE proposal_id=?",
          Int::class.java,
          proposal,
        ),
      )
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `projection revalidates session after users row barrier`() {
    val coach = actor()
    val recipient = actor()
    val relation = relation(coach, recipient)
    val locked = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val revoking =
        pool.submit {
          tx.executeWithoutResult {
            db.query("SELECT id FROM users WHERE id=? FOR UPDATE", { _, _ -> Unit }, coach.id)
            locked.countDown()
            assertTrue(release.await(8, TimeUnit.SECONDS))
            db.update("UPDATE sessions SET revoked_at=now() WHERE id=?", coach.session)
          }
        }
      assertTrue(locked.await(5, TimeUnit.SECONDS))
      val reading =
        pool.submit<HttpResponse<String>> { call(coach, "/coach-relations/$relation/calendar") }
      waitingLock()
      release.countDown()
      revoking.get(10, TimeUnit.SECONDS)
      code(reading.get(10, TimeUnit.SECONDS), 401, "unauthorized")
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `calendar projection preserves empty and thousand planned sets and binds recipient revision`() {
    val coach = actor()
    val recipient = actor()
    val relation = relation(coach, recipient)
    val ex = exercise()
    val routine = UUID.randomUUID()
    for (count in listOf(0, 1000)) {
      putRecord(
        recipient,
        "routine",
        routine,
        mapOf(
          "name" to "План",
          "note" to "PRIVATE",
          "exercises" to
            if (count == 0) emptyList<Any>()
            else
              listOf(mapOf("exerciseId" to ex, "plannedSets" to List(count) { mapOf("reps" to 1) })),
        ),
      )
      if (count == 0)
        repeat(2) {
          putRecord(
            recipient,
            "calendar_plan",
            UUID.randomUUID(),
            mapOf("routineId" to routine, "startsAtMillis" to 100, "timeZoneId" to "UTC"),
          )
        }
      val page = call(coach, "/coach-relations/$relation/calendar?limit=1")
      assertEquals(200, page.statusCode(), page.body())
      assertFalse(page.body().contains("PRIVATE"))
      val tree = json.readTree(page.body())
      val exercises = tree["items"][0]["exercises"]
      if (count == 0) assertEquals(0, exercises.size())
      else assertEquals(1000, exercises[0]["plannedSetCount"].asInt())
      val cursor = tree["nextCursor"].asString()
      db.update("UPDATE sync_heads SET revision=revision+1 WHERE user_id=?", recipient.id)
      code(
        call(coach, "/coach-relations/$relation/calendar?limit=1&cursor=$cursor"),
        409,
        "relation_snapshot_changed",
      )
    }
  }

  @Test
  fun `key rotation retains previous verification until retirement and rejects expired cursors`() {
    val pepper = "test-pepper-with-at-least-thirty-two-bytes"
    val now = java.time.Instant.ofEpochMilli(2000000000000)
    val env =
      org.springframework.mock.env.MockEnvironment().withProperty("gym.token-pepper", pepper)
    val first = CoachRelationCrypto(env, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
    val token =
      first.cursor(linkedMapOf("expiresAtMillis" to (now.toEpochMilli() + 900000).toString()))
    val rotated =
      org.springframework.mock.env
        .MockEnvironment()
        .withProperty("gym.token-pepper", pepper + "rotated")
        .withProperty("gym.coach-relations.key-version", "2")
        .withProperty("gym.coach-relations.retained-key-versions", "1")
        .withProperty("gym.coach-relations.keys.1.pepper", pepper)
        .withProperty(
          "gym.coach-relations.keys.1.retire-at-millis",
          (now.toEpochMilli() + 691200000).toString(),
        )
    assertEquals(
      "1",
      CoachRelationCrypto(rotated, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
        .decode(token)["k"],
    )
    val expired =
      Assertions.assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
        CoachRelationCrypto(
            rotated,
            java.time.Clock.fixed(now.plusSeconds(901), java.time.ZoneOffset.UTC),
          )
          .decode(token)
      }
    assertEquals("cursor_expired", expired.code)
    val retired =
      CoachRelationCrypto(
        rotated,
        java.time.Clock.fixed(now.plusSeconds(691200), java.time.ZoneOffset.UTC),
      )
    assertEquals(setOf(2), retired.versions())
    assertEquals(
      "cursor_key_retired",
      Assertions.assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
          retired.decode(token)
        }
        .code,
    )
  }

  @Test
  fun `legacy null origin is denied while new null origins and mutations are rejected`() {
    val coach = actor()
    val recipient = actor()
    val relation = relation(coach, recipient)
    val draft = draft(exercise())
    val proposal = coachProposal(coach, relation, draft)
    Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
      db.update("UPDATE training_proposals SET origin_relation_id=NULL WHERE id=?", proposal)
    }
    // Simulate an existing pre-012 row; no production code can create this state.
    tx.executeWithoutResult {
      db.execute("ALTER TABLE training_proposals DISABLE TRIGGER coach_proposal_origin")
      db.execute("ALTER TABLE training_proposal_versions DISABLE TRIGGER coach_version_origin")
      db.update("UPDATE training_proposals SET origin_relation_id=NULL WHERE id=?", proposal)
      db.update(
        "UPDATE training_proposal_versions SET origin_relation_id=NULL WHERE proposal_id=?",
        proposal,
      )
      db.execute("ALTER TABLE training_proposals ENABLE TRIGGER coach_proposal_origin")
      db.execute("ALTER TABLE training_proposal_versions ENABLE TRIGGER coach_version_origin")
    }
    code(approve(recipient, proposal, draft), 403, "forbidden")
  }

  @Test
  fun `coach deletion terminalizes pending origin and retains accepted recipient records`() {
    val coach = actor()
    val recipient = actor()
    val relation = relation(coach, recipient)
    val draft = draft(exercise())
    val accepted = coachProposal(coach, relation, draft)
    val pending = coachProposal(coach, relation, draft)
    assertEquals(200, approve(recipient, accepted, draft).statusCode())
    auth.delete(coach.identity(), deleteCode(coach))
    assertEquals(
      "REVOKED",
      db.queryForObject(
        "SELECT status FROM training_proposals WHERE id=?",
        String::class.java,
        pending,
      ),
    )
    assertEquals(200, call(recipient, "/training-proposals/$accepted/accepted-result").statusCode())
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        recipient.id,
      ),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM training_proposal_authors WHERE live_account_id=?",
        Int::class.java,
        coach.id,
      ),
    )
    Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
      db.update("DELETE FROM coach_relations WHERE id=?", relation)
    }
  }

  @Test
  fun `directory revision lock prevents counterpart deletion from mixing page snapshots`() {
    val coach = actor()
    val recipient = actor()
    val relation = relation(coach, recipient)
    val code = deleteCode(recipient)
    val locked = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val reading =
        pool.submit<tech.valerochkagym.controller.model.CoachDirectoryPage> {
          tx.execute {
            relationRows.guards(coach.id)
            relationRows.session(coach.identity())
            relationRows.revision(coach.id)
            locked.countDown()
            assertTrue(release.await(8, TimeUnit.SECONDS))
            relationService.directory(coach.identity(), "clients", 20, null)
          }
        }
      assertTrue(locked.await(5, TimeUnit.SECONDS))
      val deleting = pool.submit { auth.delete(recipient.identity(), code) }
      waitingLock()
      release.countDown()
      val page = reading.get(10, TimeUnit.SECONDS)
      assertEquals("ACTIVE", page.items.single().state)
      deleting.get(10, TimeUnit.SECONDS)
      val next = relationService.directory(coach.identity(), "clients", 20, null)
      assertEquals("REVOKED", next.items.single().state)
      assertTrue(next.directoryRevision > page.directoryRevision)
      assertEquals(relation, next.items.single().relationId)
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `relation scoped proposal revoke is ledgered and terminal replay survives relation revoke`() {
    val coach = actor()
    val recipient = actor()
    val relation = relation(coach, recipient)
    val proposal = coachProposal(coach, relation, draft(exercise()))
    val path = "/coach-relations/$relation/training-proposals/$proposal/revoke"
    code(
      call(
        coach,
        path,
        json.writeValueAsString(mapOf("operationId" to UUID.randomUUID(), "expectedVersion" to 0)),
      ),
      400,
      "invalid_request",
    )
    val raw =
      json.writeValueAsString(mapOf("operationId" to UUID.randomUUID(), "expectedVersion" to 1))
    val response = call(coach, path, raw)
    assertEquals(200, response.statusCode(), response.body())
    assertEquals("REVOKED", json.readTree(response.body())["status"].asString())
    call(recipient, "/coach-relations/$relation/revoke", operation())
    val replay = call(coach, path, raw)
    assertEquals(200, replay.statusCode(), replay.body())
    assertEquals(json.readTree(response.body()), json.readTree(replay.body()))
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM coach_relation_operations WHERE action='REVOKE_COACH_PROPOSAL' AND raw_request IS NOT NULL",
        Int::class.java,
      ),
    )
  }
}
