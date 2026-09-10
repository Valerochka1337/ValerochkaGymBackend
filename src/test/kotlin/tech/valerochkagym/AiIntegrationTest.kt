package tech.valerochkagym

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.*
import javax.imageio.ImageIO
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.service.ai.*
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Testcontainers
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  classes = [Application::class, AiIntegrationTest.Fakes::class],
)
class AiIntegrationTest {
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

  class FakeProvider : AiProvider {
    override var available = true
    var calls = 0
    var handler: (AiProviderInput) -> JsonNode = { error("test handler absent") }

    override fun generate(input: AiProviderInput): JsonNode {
      calls++
      return handler(input)
    }
  }

  @TestConfiguration
  class Fakes {
    @Bean @Primary fun provider() = FakeProvider()
  }

  @Autowired lateinit var actions: AiActionService
  @Autowired lateinit var provider: FakeProvider
  @Autowired lateinit var json: ObjectMapper
  @Autowired lateinit var db: JdbcTemplate
  @Autowired lateinit var crypto: Crypto
  @LocalServerPort var port = 0
  val client = HttpClient.newHttpClient()

  data class Owner(val id: UUID, val session: UUID, val token: String)

  @BeforeEach
  fun reset() {
    // Match AuthService.cleanup lock order even when its initial scheduled run overlaps reset.
    db.execute(
      "TRUNCATE sessions,refresh_tokens,email_challenges,google_nonces,rate_limits,users,standard_records CASCADE"
    )
    db.update("UPDATE catalog_state SET revision=0,active=false")
    provider.calls = 0
    provider.available = true
    provider.handler = {
      json.readTree(
        """{"result":{"kind":"NEW","name":"Присед","type":"STRENGTH","muscles":[{"muscle":"QUADS","contribution":100}]}}"""
      )
    }
  }

  fun owner(): Owner {
    val id = UUID.randomUUID()
    val session = UUID.randomUUID()
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    db.update("INSERT INTO users(id,email,email_verified) VALUES (?,?,true)", id, "$id@example.com")
    db.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,'test',?,TIMESTAMPTZ '2100-01-01',TIMESTAMPTZ '2100-01-01')",
      session,
      id,
      crypto.hash(token),
    )
    db.update("INSERT INTO sync_heads(user_id,revision) VALUES (?,0)", id)
    return Owner(id, session, token)
  }

  private fun enableDisclosure(a: Owner) {
    db.update("INSERT INTO health_owner_state(owner_id) VALUES (?) ON CONFLICT DO NOTHING", a.id)
    db.update(
      "INSERT INTO health_ai_disclosures VALUES (?,1,1,true,1) ON CONFLICT(owner_id) DO UPDATE SET enabled=true",
      a.id,
    )
  }

  fun call(path: String, owner: Owner? = null, body: Any? = null): HttpResponse<String> {
    val request =
      HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        .header("Content-Type", "application/json")
    owner?.let { request.header("Authorization", "Bearer ${it.token}") }
    if (path == "/v1/ai/inbody-drafts") request.header("X-Health-AI-Disclosure-Revision", "1")
    request.method(
      if (body == null) "GET" else "POST",
      body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
        ?: HttpRequest.BodyPublishers.noBody(),
    )
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
  }

  fun request() =
    mapOf(
      "requestId" to UUID.randomUUID().toString(),
      "expectedRevision" to 0,
      "expectedCatalogRevision" to 0,
      "description" to "Присед",
    )

  @Test
  fun `status auth unconfigured and legacy remain independent`() {
    assertEquals(401, call("/v1/ai/status").statusCode())
    val a = owner()
    provider.available = false
    val status = call("/v1/ai/status", a)
    assertEquals(200, status.statusCode())
    assertEquals("UNCONFIGURED", json.readTree(status.body())["availability"].asString())
    assertEquals(503, call("/v1/ai/exercise-drafts", a, request()).statusCode())
    assertEquals(0, provider.calls)
    assertEquals(200, call("/v1/sync", a).statusCode())
    assertEquals(200, call("/actuator/health").statusCode())
  }

  @Test
  fun `draft returns exact wrapper and never writes aggregates or ledger`() {
    val a = owner()
    val body = request()
    val response = call("/v1/ai/exercise-drafts", a, body)
    assertEquals(200, response.statusCode(), response.body())
    val draft = json.readTree(response.body())
    assertEquals(body["requestId"], draft["requestId"].asString())
    assertEquals("NEW", draft["result"]["kind"].asString())
    assertEquals(0, db.queryForObject("SELECT count(*) FROM records", Int::class.java))
    assertEquals(0, db.queryForObject("SELECT count(*) FROM sync_operations", Int::class.java))
    assertEquals(0, db.queryForObject("SELECT revision FROM sync_heads", Int::class.java))
    assertEquals(
      400,
      call("/v1/ai/exercise-drafts", a, body + mapOf("prompt" to "forged")).statusCode(),
    )
    assertEquals(
      400,
      call(
          "/v1/ai/exercise-drafts",
          a,
          body + mapOf("requestId" to "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
        )
        .statusCode(),
    )
    assertEquals(
      400,
      call("/v1/ai/exercise-drafts", a, body + mapOf("expectedRevision" to -1)).statusCode(),
    )
  }

  @Test
  fun `calendar draft persists one pending proposal and replays its original receipt`() {
    val a = owner()
    val exercise = UUID.randomUUID()
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'exercise',?,0,false,?::jsonb)",
      a.id,
      exercise,
      "{\"name\":\"Press\",\"type\":\"STRENGTH\",\"muscles\":[{\"muscle\":\"UPPER_CHEST\",\"contribution\":100}],\"equipmentIds\":[],\"equipmentRequirementState\":\"KNOWN\"}",
    )
    provider.handler = {
      json.readTree(
        "{\"result\":{\"name\":\"AI Press\",\"exercises\":[{\"exerciseId\":\"$exercise\",\"restSeconds\":90,\"plannedSets\":[{\"reps\":8,\"durationSec\":null}]}]}}"
      )
    }
    val body =
      linkedMapOf<String, Any?>(
        "requestId" to UUID.randomUUID().toString(),
        "expectedRevision" to 0,
        "expectedCatalogRevision" to 0,
        "startsAtMillis" to (System.currentTimeMillis() + 86_400_000),
        "timeZoneId" to "UTC",
        "gymIds" to emptyList<String>(),
        "excludedExerciseIds" to emptyList<String>(),
        "excludedEquipmentIds" to emptyList<String>(),
        "priorityMuscles" to listOf("UPPER_CHEST"),
        "includeNotes" to false,
        "availableDurationMinutes" to 45,
        "currentState" to null,
        "preferences" to null,
      )
    val first = call("/v1/ai/calendar-drafts", a, body)
    assertEquals(200, first.statusCode(), first.body())
    val replay = call("/v1/ai/calendar-drafts", a, body)
    assertEquals(200, replay.statusCode(), replay.body())
    assertEquals(first.body(), replay.body())
    assertEquals(1, provider.calls)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    assertEquals(
      "SUCCEEDED",
      db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
    )
  }

  fun addExercise(owner: Owner, id: UUID, name: String) {
    val payload =
      json.writeValueAsString(
        mapOf("name" to name, "type" to "STRENGTH", "muscles" to emptyList<Any>())
      )
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'exercise',?,0,false,?::jsonb)",
      owner.id,
      id,
      payload,
    )
  }

  @Test
  fun `raw description limit precedes trimming and blank descriptions are rejected`() {
    val a = owner()
    val exact = " " + "x".repeat(1998) + " "
    provider.handler = { input ->
      assertEquals("x".repeat(1998), json.readTree(input.context)["description"].asString())
      json.readTree(
        """{"result":{"kind":"NEW","name":"x","type":"STRENGTH","muscles":[{"muscle":"QUADS","contribution":100}]}}"""
      )
    }
    assertEquals(
      200,
      call("/v1/ai/exercise-drafts", a, request() + ("description" to exact)).statusCode(),
    )
    for (invalid in listOf(exact + " ", " ".repeat(2000), "")) {
      assertEquals(
        400,
        call("/v1/ai/exercise-drafts", a, request() + ("description" to invalid)).statusCode(),
      )
    }
    assertEquals(1, provider.calls)
  }

  @Test
  fun `shared action admission precedes image and context work and releases on failure and cancellation`() {
    val a = owner()
    enableDisclosure(a)
    val identity = Identity(a.id, a.session, "$a@example.com")
    fun exercise(revision: Long = 0) =
      ExerciseDraftRequest(UUID.randomUUID().toString(), revision, 0, "x")
    fun invalidImage() =
      InBodyDraftRequest(UUID.randomUUID().toString(), 0, 0, AiImage("image/jpeg", "not-base64"))
    val entered = CountDownLatch(2)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(2)
    val normal = provider.handler
    provider.handler = { input ->
      entered.countDown()
      release.await(5, TimeUnit.SECONDS)
      normal(input)
    }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val requests =
        (1..2).map {
          executor.submit<Any> {
            try {
              actions.exercise(identity, exercise())
            } finally {
              finished.countDown()
            }
          }
        }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      // Invalid image and stale context would fail differently if either expensive path ran.
      assertEquals(
        "ai_busy",
        assertThrows(ApiException::class.java) { actions.inbody(identity, invalidImage(), 1) }.code,
      )
      assertEquals(
        "ai_busy",
        assertThrows(ApiException::class.java) { actions.exercise(identity, exercise(1)) }.code,
      )
      requests.forEach { it.cancel(true) }
      assertTrue(finished.await(3, TimeUnit.SECONDS))
      provider.handler = normal
      repeat(3) {
        assertEquals(
          "invalid_image",
          assertThrows(ApiException::class.java) { actions.inbody(identity, invalidImage(), 1) }
            .code,
        )
        assertThrows(ApiException::class.java) { actions.exercise(identity, exercise(1)) }
      }
      assertEquals("NEW", actions.exercise(identity, exercise()).result["kind"].asString())
    } finally {
      release.countDown()
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
    }
  }

  fun addProfile(owner: Owner, constraint: String = "SAVED_TRAINING_CONTEXT") {
    val id = tech.valerochkagym.service.data.ProfileIdentity.syncId(owner.id.toString())
    val payload =
      json
        .readTree(javaClass.getResourceAsStream("/basic-profile-sync-contract.json"))[
          "emptyPayload"]
        .deepCopy() as tools.jackson.databind.node.ObjectNode
    payload.put("syncId", id.toString())
    payload.put("birthDate", "2000-02-29")
    payload.put("trainingGoal", "STRENGTH")
    payload.put("manualConstraints", constraint)
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'profile',?,0,false,?::jsonb)",
      owner.id,
      id,
      payload.toString(),
    )
  }

  @Test
  fun `saved profile provider projection excludes exact date identity measurements and foreign context`() {
    val a = owner()
    val b = owner()
    addProfile(a)
    addProfile(b, "FOREIGN_PROFILE")
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'measurement',?,0,false,'{\"weightKg\":123.456}'::jsonb)",
      a.id,
      UUID.randomUUID(),
    )
    val normal = provider.handler
    provider.handler = { input ->
      val context = json.readTree(input.context)
      assertEquals("STRENGTH", context["profile"]["trainingGoal"].asString())
      assertTrue(context["profile"]["ageYears"].isIntegralNumber)
      for (secret in
        listOf(
          "birthDate",
          "2000-02-29",
          "syncId",
          "ownerId",
          a.id.toString(),
          b.id.toString(),
          "FOREIGN_PROFILE",
          "weightKg",
          "123.456",
        )) assertFalse(input.context.contains(secret), secret)
      normal(input)
    }
    assertEquals(200, call("/v1/ai/exercise-drafts", a, request()).statusCode())
    provider.handler = { input ->
      db.update("UPDATE sync_heads SET revision=1 WHERE user_id=?", a.id)
      normal(input)
    }
    assertEquals(409, call("/v1/ai/exercise-drafts", a, request()).statusCode())
    val c = owner()
    provider.handler = { input ->
      assertTrue(json.readTree(input.context)["profile"].isNull)
      normal(input)
    }
    assertEquals(200, call("/v1/ai/exercise-drafts", c, request()).statusCode())
  }

  @Test
  fun `catalog context cannot include another owner and existing IDs must resolve`() {
    val a = owner()
    val b = owner()
    val mine = UUID.randomUUID()
    val foreign = UUID.randomUUID()
    addExercise(a, mine, "MINE")
    addExercise(b, foreign, "FOREIGN_PRIVATE")
    provider.handler = { input ->
      assertTrue(input.context.contains("MINE"))
      assertFalse(input.context.contains("FOREIGN_PRIVATE"))
      json.readTree("""{"result":{"kind":"EXISTING","exerciseId":"$mine"}}""")
    }
    assertEquals(200, call("/v1/ai/exercise-drafts", a, request()).statusCode())
    provider.handler = {
      json.readTree("""{"result":{"kind":"EXISTING","exerciseId":"$foreign"}}""")
    }
    assertEquals(502, call("/v1/ai/exercise-drafts", a, request()).statusCode())
  }

  @Test
  fun `revision changes before and during provider call discard stale results without held locks`() {
    val a = owner()
    assertEquals(
      409,
      call("/v1/ai/exercise-drafts", a, request() + mapOf("expectedRevision" to 1)).statusCode(),
    )
    assertEquals(0, provider.calls)
    val normal = provider.handler
    provider.handler = { input ->
      db.update("UPDATE sync_heads SET revision=1 WHERE user_id=?", a.id)
      normal(input)
    }
    assertEquals(409, call("/v1/ai/exercise-drafts", a, request()).statusCode())
    db.update("UPDATE sync_heads SET revision=0 WHERE user_id=?", a.id)
    provider.handler = { input ->
      db.update("UPDATE catalog_state SET revision=1")
      normal(input)
    }
    assertEquals(409, call("/v1/ai/exercise-drafts", a, request()).statusCode())
  }

  @Test
  fun `session revocation and owner deletion during provider discard results`() {
    val a = owner()
    val normal = provider.handler
    provider.handler = { input ->
      db.update("UPDATE sessions SET revoked_at=now() WHERE id=?", a.session)
      normal(input)
    }
    assertEquals(401, call("/v1/ai/exercise-drafts", a, request()).statusCode())
    val b = owner()
    provider.handler = { input ->
      db.update("DELETE FROM users WHERE id=?", b.id)
      normal(input)
    }
    assertTrue(call("/v1/ai/exercise-drafts", b, request()).statusCode() in listOf(401, 409))
  }

  @Test
  fun `inbody keeps nullable draft fields and image validation is local`() {
    val a = owner()
    enableDisclosure(a)
    addProfile(a)
    val bytes =
      ByteArrayOutputStream()
        .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", it) }
        .toByteArray()
    val schema = json.readTree(javaClass.getResourceAsStream("/ai/inbody-output-schema.json"))
    val fields = schema["properties"]["result"]["properties"]["draft"]["properties"]
    val draft =
      fields
        .properties()
        .associate {
          it.key to
            if (it.key == "segments")
              it.value["properties"].properties().associate { segment ->
                segment.key to
                  mapOf(
                    "leanMassKg" to null,
                    "leanPercentage" to null,
                    "fatMassKg" to null,
                    "fatPercentage" to null,
                  )
              }
            else null
        }
        .toMutableMap<String, Any?>()
    draft["weightKg"] = 70.0
    provider.handler = { input ->
      assertTrue(input.vision)
      assertEquals("Read the selected InBody photo.", input.context)
      json.valueToTree<JsonNode>(mapOf("result" to mapOf("kind" to "INBODY", "draft" to draft)))
    }
    val request =
      request().filterKeys { it != "description" } +
        mapOf(
          "image" to
            mapOf(
              "mediaType" to "image/jpeg",
              "base64" to Base64.getEncoder().encodeToString(bytes),
            )
        )
    val response = call("/v1/ai/inbody-drafts", a, request)
    assertEquals(200, response.statusCode(), response.body())
    assertTrue(json.readTree(response.body())["result"]["draft"]["bodyFatPercentage"].isNull)
    assertEquals(
      400,
      call(
          "/v1/ai/inbody-drafts",
          a,
          request + mapOf("image" to mapOf("mediaType" to "image/jpeg", "base64" to "not-base64")),
        )
        .statusCode(),
    )
    val originalHandler = provider.handler
    provider.handler = { input ->
      db.update("UPDATE health_ai_disclosures SET revision=2,enabled=false WHERE owner_id=?", a.id)
      originalHandler(input)
    }
    val revoked = call("/v1/ai/inbody-drafts", a, request)
    assertEquals(403, revoked.statusCode(), revoked.body())
    assertEquals("health_ai_consent_required", json.readTree(revoked.body())["code"].asString())
    val callsBefore = provider.calls
    assertEquals(403, call("/v1/ai/inbody-drafts", a, request).statusCode())
    assertEquals(callsBefore, provider.calls)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM records", Int::class.java))
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM records WHERE kind='measurement'", Int::class.java),
    )
  }

  @Test
  fun `body cap applies to exact and oversized chunked input and conflicting lengths are rejected`() {
    val a = owner()
    val prefix = json.writeValueAsString(request())
    fun chunked(size: Int): Int {
      val bytes = ByteArray(size) { 32 }.also { prefix.toByteArray().copyInto(it) }
      val req =
        HttpRequest.newBuilder(URI("http://localhost:$port/v1/ai/exercise-drafts"))
          .header("Authorization", "Bearer ${a.token}")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofInputStream { java.io.ByteArrayInputStream(bytes) })
          .build()
      return client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()
    }
    assertEquals(200, chunked(10 * 1024 * 1024))
    assertEquals(413, chunked(10 * 1024 * 1024 + 1))
    java.net.Socket("127.0.0.1", port).use { socket ->
      socket.soTimeout = 5000
      socket
        .getOutputStream()
        .write(
          ("POST /v1/ai/exercise-drafts HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer ${a.token}\r\nContent-Type: application/json\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n2\r\n{}\r\n0\r\n\r\n")
            .toByteArray()
        )
      val status = socket.getInputStream().bufferedReader().readLine()
      assertTrue(status.contains("400"), status)
    }
  }

  @Test
  fun `oversized context makes zero provider calls and public archived entries stay hidden`() {
    val a = owner()
    addExercise(a, UUID.randomUUID(), "x".repeat(1024 * 1024))
    assertEquals(409, call("/v1/ai/exercise-drafts", a, request()).statusCode())
    assertEquals(0, provider.calls)
    db.update("DELETE FROM records WHERE user_id=?", a.id)
    db.update("UPDATE catalog_state SET active=true")
    val active = UUID.randomUUID()
    val archived = UUID.randomUUID()
    db.update(
      "INSERT INTO standard_records(kind,id,revision,archived,payload) VALUES ('exercise',?,0,false,'{\"name\":\"PUBLIC\"}'::jsonb),('exercise',?,0,true,'{\"name\":\"ARCHIVED\"}'::jsonb)",
      active,
      archived,
    )
    provider.handler = { input ->
      assertTrue(input.context.contains("PUBLIC"))
      assertFalse(input.context.contains("ARCHIVED"))
      json.readTree("""{"result":{"kind":"EXISTING","exerciseId":"$active"}}""")
    }
    assertEquals(200, call("/v1/ai/exercise-drafts", a, request()).statusCode())
  }

  @Test
  fun `OpenAPI exports AI paths and frozen contract hash`() {
    val a = owner()
    val response = call("/v3/api-docs", a)
    assertEquals(200, response.statusCode())
    val doc = json.readTree(response.body())
    assertTrue(doc["paths"].has("/v1/ai/inbody-drafts"))
    assertTrue(doc["paths"].has("/v1/ai/calendar-drafts"))
    val file = java.nio.file.Path.of("build/reports/openapi.json")
    java.nio.file.Files.createDirectories(file.parent)
    java.nio.file.Files.writeString(file, response.body())
    val bytes = javaClass.getResourceAsStream("/ai-contract-v1.json")!!.readAllBytes()
    assertEquals(
      "f76033bf776748a37567c0a26a9c74e8cf13d215c47e2077068c0b679ae6599b",
      java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
      },
    )
    val fixture = json.readTree(bytes)
    for (name in listOf("exercise", "inbody")) assertEquals(
      fixture["provider"]["${name}OutputSchema"],
      json.readTree(javaClass.getResourceAsStream("/ai/$name-output-schema.json")),
    )
    val calendar =
      java.nio.file.Files.readAllBytes(
        java.nio.file.Path.of("src/test/resources/calendar-ai-contract.json")
      )
    assertEquals(
      "42714ea6086c8d7349543cfdb3d11cfac86d04fe67b15ec743ff388f4e31b18e",
      java.security.MessageDigest.getInstance("SHA-256").digest(calendar).joinToString("") {
        "%02x".format(it)
      },
    )
  }
}
