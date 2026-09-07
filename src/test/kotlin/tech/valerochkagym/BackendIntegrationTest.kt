package tech.valerochkagym

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
import tech.valerochkagym.auth.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Testcontainers
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  classes = [Application::class, BackendIntegrationTest.Fakes::class],
)
class BackendIntegrationTest {
  @Test
  fun `backup restores account records and Liquibase history into a separate database`() {
    val token = account()["accessToken"].asString()
    assertEquals(200, push(token, listOf(change(payload = exercise("Backup fixture")))).status)
    fun command(vararg args: String): String {
      val result = postgres.execInContainer(*args)
      assertEquals(0, result.exitCode, result.stderr)
      return result.stdout.trim()
    }
    command(
      "pg_dump",
      "-U",
      postgres.username,
      "-d",
      postgres.databaseName,
      "-Fc",
      "-f",
      "/tmp/gym-restore-test.dump",
    )
    command("createdb", "-U", postgres.username, "gym_restore_test")
    try {
      command(
        "pg_restore",
        "-U",
        postgres.username,
        "-d",
        "gym_restore_test",
        "--exit-on-error",
        "--no-owner",
        "/tmp/gym-restore-test.dump",
      )
      assertEquals(
        "Backup fixture",
        command(
          "psql",
          "-U",
          postgres.username,
          "-d",
          "gym_restore_test",
          "-Atc",
          "SELECT payload->>'name' FROM records",
        ),
      )
      assertEquals(
        "1",
        command(
          "psql",
          "-U",
          postgres.username,
          "-d",
          "gym_restore_test",
          "-Atc",
          "SELECT count(*) FROM users",
        ),
      )
      assertEquals(
        "3",
        command(
          "psql",
          "-U",
          postgres.username,
          "-d",
          "gym_restore_test",
          "-Atc",
          "SELECT count(*) FROM databasechangelog",
        ),
      )
    } finally {
      command("dropdb", "-U", postgres.username, "gym_restore_test")
    }
  }

  companion object {
    val googleKey = com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate()
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
      r.add("gym.admin-origin") { "https://admin.test" }
      r.add("gym.google-client-id") { "test-google-client" }
    }
  }

  class CapturingMailer : Mailer {
    val codes = ConcurrentHashMap<String, String>()

    override fun sendCode(email: String, purpose: String, code: String) {
      codes["$email:$purpose"] = code
    }
  }

  @TestConfiguration
  class Fakes {
    @Bean @Primary fun mailer() = CapturingMailer()

    @Bean
    @Primary
    fun decoder(): org.springframework.security.oauth2.jwt.JwtDecoder =
      org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withPublicKey(
          googleKey.toRSAPublicKey()
        )
        .build()
  }

  @LocalServerPort var port = 0
  @Autowired lateinit var json: ObjectMapper
  @Autowired lateinit var mail: CapturingMailer
  @Autowired lateinit var db: JdbcTemplate
  private val client = HttpClient.newHttpClient()

  private fun googleBody(
    email: String = "google@example.com",
    audience: String = "test-google-client",
    issuer: String = "https://accounts.google.com",
    expired: Boolean = false,
    wrongKey: Boolean = false,
    subject: String = "google-subject",
  ): Map<String, String> {
    val nonce = call("POST", "/auth/google/nonce").body!!["nonce"].asString()
    val claims =
      com.nimbusds.jwt.JWTClaimsSet.Builder()
        .subject(subject)
        .issuer(issuer)
        .audience(audience)
        .expirationTime(
          java.util.Date.from(java.time.Instant.now().plusSeconds(if (expired) -300 else 300))
        )
        .claim("email", email)
        .claim("email_verified", true)
        .claim("nonce", nonce)
        .build()
    val token =
      com.nimbusds.jwt.SignedJWT(
        com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256),
        claims,
      )
    val key =
      if (wrongKey) com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate() else googleKey
    token.sign(com.nimbusds.jose.crypto.RSASSASigner(key))
    return mapOf("idToken" to token.serialize(), "nonce" to nonce)
  }

  @Test
  fun `Google verifies signature audience issuer expiration and one time nonce`() {
    assertEquals(401, call("POST", "/auth/google", googleBody(audience = "other-client")).status)
    assertEquals(
      401,
      call("POST", "/auth/google", googleBody(issuer = "https://attacker.example")).status,
    )
    assertEquals(401, call("POST", "/auth/google", googleBody(expired = true)).status)
    assertEquals(401, call("POST", "/auth/google", googleBody(wrongKey = true)).status)
    val body = googleBody()
    assertEquals(200, call("POST", "/auth/google", body).status)
    assertEquals(401, call("POST", "/auth/google", body).status)
  }

  @Test
  fun `Google linking requires the existing account session`() {
    val a = account("google@example.com")
    assertEquals(409, call("POST", "/auth/google", googleBody()).status)
    val linked = call("POST", "/me/google", googleBody(), a["accessToken"].asString())
    assertEquals(200, linked.status)
    assertEquals(a["userId"], linked.body!!["userId"])
    assertEquals(a["userId"], call("POST", "/auth/google", googleBody()).body!!["userId"])
  }

  @Test
  fun `Android generated aggregates round trip through the server contract`() {
    val fixture = json.readTree(javaClass.getResourceAsStream("/android-snapshot.json"))
    val token = account()["accessToken"].asString()
    val changes =
      fixture["records"].toList().map { r ->
        change(r["id"].asString(), 0, r["payload"], r["kind"].asString())
      }
    val result = push(token, changes)
    assertEquals(200, result.status, result.toString())
    val restored =
      call("GET", "/sync", token = token).body!!["records"].associateBy { it["id"].asString() }
    fixture["records"].forEach {
      assertEquals(it["payload"], restored.getValue(it["id"].asString())["payload"])
    }
  }

  @Test
  fun `OpenAPI describes auth and synchronization endpoints`() {
    val token = account()["accessToken"].asString()
    val request =
      HttpRequest.newBuilder(URI("http://localhost:$port/v3/api-docs"))
        .header("Authorization", "Bearer $token")
        .GET()
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    assertEquals(200, response.statusCode())
    val doc = json.readTree(response.body())
    assertTrue(doc["paths"].has("/v1/auth/login"))
    assertTrue(doc["paths"].has("/v1/sync"))
    val output = java.nio.file.Path.of("build/reports/openapi.json")
    java.nio.file.Files.createDirectories(output.parent)
    java.nio.file.Files.writeString(output, response.body())
  }

  data class Reply(val status: Int, val body: JsonNode?)

  private fun call(method: String, path: String, body: Any? = null, token: String? = null): Reply {
    val builder =
      HttpRequest.newBuilder(URI("http://localhost:$port/v1$path"))
        .header("Content-Type", "application/json")
    if (token != null) builder.header("Authorization", "Bearer $token")
    val response =
      client.send(
        builder
          .method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
              ?: HttpRequest.BodyPublishers.noBody(),
          )
          .build(),
        HttpResponse.BodyHandlers.ofString(),
      )
    return Reply(
      response.statusCode(),
      response.body().takeIf { it.isNotBlank() }?.let(json::readTree),
    )
  }

  @BeforeEach
  fun clean() {
    db.execute("TRUNCATE users,rate_limits,email_challenges,google_nonces,admin_audit CASCADE")
    mail.codes.clear()
  }

  private fun account(email: String = "${UUID.randomUUID()}@example.com"): JsonNode {
    val body = mapOf("email" to email, "password" to "a long unique password")
    assertEquals(200, call("POST", "/auth/register", body).status)
    assertEquals(
      200,
      call("POST", "/auth/verify", mapOf("email" to email, "code" to mail.codes["$email:verify"]))
        .status,
    )
    val login = call("POST", "/auth/login", body)
    assertEquals(200, login.status, login.toString())
    return login.body!!
  }

  private fun exercise(name: String = "Присед") =
    mapOf(
      "name" to name,
      "muscleGroup" to "LEGS",
      "type" to "STRENGTH",
      "isCustom" to true,
      "updatedAt" to 1L,
      "needsMuscleMapReview" to false,
      "equipmentRequirementState" to "KNOWN",
      "muscles" to emptyList<Any>(),
      "equipmentIds" to emptyList<String>(),
    )

  private fun change(
    id: String = UUID.randomUUID().toString(),
    revision: Long = 0,
    payload: Any? = exercise(),
    kind: String = "exercise",
  ) =
    mapOf(
      "kind" to kind,
      "id" to id,
      "baseRevision" to revision,
      "deleted" to (payload == null),
      "payload" to payload,
    )

  private fun push(
    token: String,
    changes: List<Any>,
    operation: String = UUID.randomUUID().toString(),
  ) = call("POST", "/sync", mapOf("operationId" to operation, "changes" to changes), token)

  @Test
  fun `registration requires verification and duplicate registration cannot replace password`() {
    val email = "test@example.com"
    val body = mapOf("email" to email, "password" to "a long unique password")
    assertEquals(200, call("POST", "/auth/register", body).status)
    assertEquals(403, call("POST", "/auth/login", body).status)
    call("POST", "/auth/register", body + mapOf("password" to "attacker password 123"))
    call("POST", "/auth/verify", mapOf("email" to email, "code" to mail.codes["$email:verify"]))
    assertEquals(200, call("POST", "/auth/login", body).status)
    assertEquals(
      401,
      call("POST", "/auth/login", body + mapOf("password" to "attacker password 123")).status,
    )
  }

  @Test
  fun `access is isolated for two users including object references`() {
    val a = account()["accessToken"].asString()
    val b = account()["accessToken"].asString()
    val id = UUID.randomUUID().toString()
    assertEquals(200, push(a, listOf(change(id))).status)
    assertEquals(404, call("GET", "/records/exercise/$id", token = b).status)
    assertEquals(0, call("GET", "/sync", token = b).body!!["records"].size())
    val gym =
      mapOf(
        "name" to "Зал",
        "updatedAt" to 1,
        "inventoryConfigured" to false,
        "equipmentIds" to emptyList<String>(),
        "exerciseIds" to listOf(id),
      )
    assertEquals(400, push(b, listOf(change(payload = gym, kind = "gym"))).status)
    assertEquals(401, call("GET", "/sync").status)
    assertEquals(401, call("GET", "/sync", token = "forged").status)
  }

  @Test
  fun `refresh rotates and reuse revokes the whole session`() {
    val a = account()
    val old = mapOf("refreshToken" to a["refreshToken"].asString())
    val refreshed = call("POST", "/auth/refresh", old)
    assertEquals(200, refreshed.status)
    assertEquals(401, call("POST", "/auth/refresh", old).status)
    assertEquals(401, call("GET", "/me", token = refreshed.body!!["accessToken"].asString()).status)
    assertEquals(
      401,
      call(
          "POST",
          "/auth/refresh",
          mapOf("refreshToken" to refreshed.body["refreshToken"].asString()),
        )
        .status,
    )
  }

  @Test
  fun `logout immediately invalidates access and refresh`() {
    val a = account()
    val access = a["accessToken"].asString()
    assertEquals(200, call("POST", "/logout", token = access).status)
    assertEquals(401, call("GET", "/me", token = access).status)
    assertEquals(
      401,
      call("POST", "/auth/refresh", mapOf("refreshToken" to a["refreshToken"].asString())).status,
    )
  }

  @Test
  fun `expired access cannot read data`() {
    val a = account()
    db.update("UPDATE sessions SET access_expires_at=now()-interval '1 second'")
    assertEquals(401, call("GET", "/sync", token = a["accessToken"].asString()).status)
  }

  @Test
  fun `password reset is one time and revokes previous sessions`() {
    val a = account()
    val email = a["email"].asString()
    call("POST", "/auth/password/request", mapOf("email" to email))
    val reset =
      mapOf(
        "email" to email,
        "code" to mail.codes["$email:reset"],
        "password" to "a different password",
      )
    assertEquals(200, call("POST", "/auth/password/reset", reset).status)
    assertEquals(400, call("POST", "/auth/password/reset", reset).status)
    assertEquals(401, call("GET", "/me", token = a["accessToken"].asString()).status)
    assertEquals(
      200,
      call("POST", "/auth/login", mapOf("email" to email, "password" to "a different password"))
        .status,
    )
  }

  @Test
  fun `code attempts remain counted when validation fails`() {
    call(
      "POST",
      "/auth/register",
      mapOf("email" to "code@example.com", "password" to "a long unique password"),
    )
    repeat(5) {
      call("POST", "/auth/verify", mapOf("email" to "code@example.com", "code" to "invalid"))
    }
    assertEquals(
      400,
      call(
          "POST",
          "/auth/verify",
          mapOf("email" to "code@example.com", "code" to mail.codes["code@example.com:verify"]),
        )
        .status,
    )
  }

  @Test
  fun `retries are idempotent and conflicting reuse is rejected`() {
    val a = account()["accessToken"].asString()
    val id = UUID.randomUUID().toString()
    val op = UUID.randomUUID().toString()
    val first = push(a, listOf(change(id)), op)
    assertEquals(200, first.status)
    assertEquals(first, push(a, listOf(change(id)), op))
    assertEquals(409, push(a, listOf(change(id, payload = exercise("Другое"))), op).status)
    assertEquals(1, call("GET", "/sync", token = a).body!!["records"].size())
  }

  @Test
  fun `concurrent writers produce one winner and a conflict`() {
    val token = account()["accessToken"].asString()
    val id = UUID.randomUUID().toString()
    Executors.newFixedThreadPool(2).use { executor ->
      val tasks = (1..2).map { executor.submit<Int> { push(token, listOf(change(id))).status } }
      assertEquals(listOf(200, 409), tasks.map { it.get() }.sorted())
    }
  }

  @Test
  fun `batch is atomic and deletion propagates without resurrection`() {
    val a = account()["accessToken"].asString()
    val id = UUID.randomUUID().toString()
    assertEquals(
      400,
      push(a, listOf(change(id), change(payload = exercise() + mapOf("unexpected" to true)))).status,
    )
    assertEquals(0, call("GET", "/sync", token = a).body!!["records"].size())
    val rev = push(a, listOf(change(id))).body!!["revision"].asLong()
    assertEquals(200, push(a, listOf(change(id, rev, null))).status)
    assertEquals(409, push(a, listOf(change(id))).status)
    val changes = call("GET", "/sync/changes?after=$rev", token = a).body!!
    assertTrue(changes["records"][0]["deleted"].asBoolean())
  }

  @Test
  fun `pagination includes all records sharing a revision`() {
    val token = account()["accessToken"].asString()
    push(token, (1..5).map { change() })
    var cursor: String? = null
    val ids = mutableSetOf<String>()
    do {
      val page =
        call("GET", "/sync/changes?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""), token = token)
          .body!!
      page["records"].forEach { ids.add(it["id"].asString()) }
      cursor = page["nextCursor"]?.takeUnless { it.isNull }?.asString()
    } while (cursor != null)
    assertEquals(5, ids.size)
  }

  @Test
  fun `Liquibase has applied auth sync and admin changesets`() {
    assertEquals(3, db.queryForObject("SELECT count(*) FROM databasechangelog", Int::class.java))
  }

  @Test
  fun `program inventory rules are validated against the final atomic batch`() {
    val token = account()["accessToken"].asString()
    val exerciseId = UUID.randomUUID().toString()
    val gymId = UUID.randomUUID().toString()
    val gym =
      mapOf(
        "name" to "Зал",
        "updatedAt" to 1,
        "inventoryConfigured" to true,
        "exerciseIds" to emptyList<String>(),
        "equipmentIds" to emptyList<String>(),
      )
    val first =
      push(
        token,
        listOf(
          change(exerciseId, payload = exercise() + mapOf("equipmentIds" to listOf("barbell"))),
          change(gymId, payload = gym, kind = "gym"),
        ),
      )
    assertEquals(200, first.status)
    val routine =
      mapOf(
        "name" to "Программа",
        "note" to "",
        "updatedAt" to 1,
        "gymIds" to listOf(gymId),
        "exercises" to
          listOf(
            mapOf(
              "exerciseId" to exerciseId,
              "position" to 0,
              "restSeconds" to 60,
              "plannedSets" to emptyList<Any>(),
            )
          ),
      )
    assertEquals(400, push(token, listOf(change(payload = routine, kind = "routine"))).status)
    val equipped = gym + mapOf("equipmentIds" to listOf("barbell"))
    assertEquals(
      200,
      push(
          token,
          listOf(
            change(gymId, first.body!!["revision"].asLong(), equipped, "gym"),
            change(payload = routine, kind = "routine"),
          ),
        )
        .status,
    )
  }

  @Test
  fun `deleting account requires email proof and cascades data`() {
    val a = account()
    val token = a["accessToken"].asString()
    push(token, listOf(change()))
    call("POST", "/me/delete-code", token = token)
    assertEquals(
      200,
      call("DELETE", "/me", mapOf("code" to mail.codes["${a["email"].asString()}:delete"]), token)
        .status,
    )
    assertEquals(0, db.queryForObject("SELECT count(*) FROM records", Int::class.java))
    assertEquals(401, call("GET", "/me", token = token).status)
  }

  data class BrowserSession(val cookie: String, val csrf: String, val userId: UUID)

  data class AdminReply(val status: Int, val body: JsonNode?, val response: HttpResponse<String>)

  private fun adminCall(
    method: String,
    path: String,
    body: Any? = null,
    browser: BrowserSession? = null,
    origin: String? = "https://admin.test",
    csrf: String? = browser?.csrf,
    bearer: String? = null,
  ): AdminReply {
    val builder =
      HttpRequest.newBuilder(URI("http://localhost:$port/admin$path"))
        .header("Content-Type", "application/json")
    if (browser != null) builder.header("Cookie", browser.cookie)
    if (origin != null) builder.header("Origin", origin)
    if (csrf != null) builder.header("X-CSRF-Token", csrf)
    if (bearer != null) builder.header("Authorization", "Bearer $bearer")
    val response =
      client.send(
        builder
          .method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
              ?: HttpRequest.BodyPublishers.noBody(),
          )
          .build(),
        HttpResponse.BodyHandlers.ofString(),
      )
    return AdminReply(
      response.statusCode(),
      response.body().takeIf { it.startsWith("{") || it.startsWith("[") }?.let(json::readTree),
      response,
    )
  }

  private fun administrator(): BrowserSession {
    val user = account()
    db.update(
      "UPDATE users SET is_admin=true WHERE id=?",
      UUID.fromString(user["userId"].asString()),
    )
    val login =
      adminCall(
        "POST",
        "/api/login",
        mapOf("email" to user["email"].asString(), "password" to "a long unique password"),
      )
    assertEquals(200, login.status, login.response.body())
    return BrowserSession(
      login.response.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';'),
      login.body!!["csrfToken"].asString(),
      UUID.fromString(user["userId"].asString()),
    )
  }

  @Test
  fun `admin pages are public but APIs reject ordinary bearer accounts and enforce origin`() {
    val ordinary = account()
    assertEquals(200, adminCall("GET", "/").status)
    val page = adminCall("GET", "/index.html")
    assertEquals(200, page.status)
    assertTrue(
      page.response
        .headers()
        .firstValue("Content-Security-Policy")
        .orElseThrow()
        .contains("frame-ancestors 'none'")
    )
    assertEquals(401, adminCall("GET", "/api/users").status)
    assertEquals(
      401,
      adminCall("GET", "/api/users", bearer = ordinary["accessToken"].asString()).status,
    )
    val body =
      mapOf("email" to ordinary["email"].asString(), "password" to "a long unique password")
    assertEquals(403, adminCall("POST", "/api/login", body, origin = "https://evil.test").status)
    assertEquals(403, adminCall("POST", "/api/login", body, origin = null).status)
    assertEquals(403, adminCall("POST", "/api/login", body).status)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM admin_sessions", Int::class.java))
  }

  @Test
  fun `admin session cookie is protected and role revocation and expiry take effect immediately`() {
    val browser = administrator()
    val login =
      adminCall(
        "POST",
        "/api/login",
        mapOf(
          "email" to
            db.queryForObject(
              "SELECT email FROM users WHERE id=?",
              String::class.java,
              browser.userId,
            ),
          "password" to "a long unique password",
        ),
      )
    val cookie = login.response.headers().firstValue("Set-Cookie").orElseThrow()
    listOf("HttpOnly", "Secure", "SameSite=Strict", "Path=/", "Max-Age=28800").forEach {
      assertTrue(cookie.contains(it), cookie)
    }
    val session = adminCall("GET", "/api/session", browser = browser)
    assertEquals(browser.csrf, session.body!!["csrfToken"].asString())
    assertEquals("no-store", session.response.headers().firstValue("Cache-Control").orElseThrow())
    assertEquals(200, adminCall("GET", "/api/users", browser = browser).status)
    db.update("UPDATE users SET is_admin=false WHERE id=?", browser.userId)
    assertEquals(401, adminCall("GET", "/api/users", browser = browser).status)
    db.update("UPDATE users SET is_admin=true WHERE id=?", browser.userId)
    db.update("UPDATE admin_sessions SET expires_at=now()-interval '1 second'")
    assertEquals(401, adminCall("GET", "/api/users", browser = browser).status)
  }

  @Test
  fun `admin edits enter Android sync once and preserve audit on conflict`() {
    val browser = administrator()
    val owner = account()
    val ownerId = owner["userId"].asString()
    val token = owner["accessToken"].asString()
    val id = UUID.randomUUID().toString()
    assertEquals(200, push(token, listOf(change(id = id))).status)
    val path = "/api/users/$ownerId/records/exercise/$id"
    val body =
      mapOf(
        "operationId" to UUID.randomUUID(),
        "baseRevision" to 1,
        "payload" to exercise("Исправленный присед"),
        "reason" to "Исправлено название",
      )
    assertEquals(403, adminCall("PUT", path, body, browser, csrf = null).status)
    assertEquals(403, adminCall("PUT", path, body, browser, csrf = "forged").status)
    assertEquals(403, adminCall("PUT", path, body, browser, origin = "https://evil.test").status)
    val result = adminCall("PUT", path, body, browser)
    assertEquals(200, result.status, result.response.body())
    assertEquals(2, result.body!!["revision"].asInt())
    assertEquals(result.body, adminCall("PUT", path, body, browser).body)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM admin_audit", Int::class.java))
    val snapshot = call("GET", "/sync", token = token).body!!
    assertEquals("Исправленный присед", snapshot["records"][0]["payload"]["name"].asString())
    assertEquals(2, snapshot["records"][0]["revision"].asInt())
    assertEquals(
      409,
      push(token, listOf(change(id = id, revision = 1, payload = exercise("Телефон")))).status,
    )
    assertEquals(
      409,
      adminCall("PUT", path, body + mapOf("operationId" to UUID.randomUUID()), browser).status,
    )
    assertEquals(
      409,
      adminCall("PUT", path, body + mapOf("reason" to "Другая причина"), browser).status,
    )
    val audit = adminCall("GET", "/api/audit", browser = browser).body!!["items"][0]
    val detail = adminCall("GET", "/api/audit/" + audit["id"].asLong(), browser = browser).body!!
    assertEquals("Присед", detail["before_payload"]["name"].asString())
    assertEquals("Исправленный присед", detail["after_payload"]["name"].asString())
    assertEquals(1, db.queryForObject("SELECT count(*) FROM admin_audit", Int::class.java))
    db.update("DELETE FROM users WHERE id=?", UUID.fromString(ownerId))
    assertEquals(1, db.queryForObject("SELECT count(*) FROM admin_audit", Int::class.java))
  }

  @Test
  fun `admin creates gyms and rejects cross owner references without partial audit`() {
    val browser = administrator()
    val owner = account()
    val other = account()
    val ownerId = owner["userId"].asString()
    val foreign = UUID.randomUUID().toString()
    push(other["accessToken"].asString(), listOf(change(id = foreign)))
    val id = UUID.randomUUID().toString()
    val gym =
      mapOf(
        "name" to "Новый зал",
        "updatedAt" to 1,
        "inventoryConfigured" to false,
        "exerciseIds" to listOf(foreign),
        "equipmentIds" to emptyList<String>(),
      )
    val path = "/api/users/$ownerId/records/gym/$id"
    val body =
      mapOf(
        "operationId" to UUID.randomUUID(),
        "baseRevision" to 0,
        "payload" to gym,
        "reason" to "Добавлен новый зал",
      )
    assertEquals(400, adminCall("PUT", path, body, browser).status)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM admin_audit", Int::class.java))
    assertEquals(
      0,
      call("GET", "/sync", token = owner["accessToken"].asString()).body!!["records"].size(),
    )
    assertEquals(
      200,
      adminCall(
          "PUT",
          path,
          body + mapOf("payload" to gym + mapOf("exerciseIds" to emptyList<String>())),
          browser,
        )
        .status,
    )
    assertEquals(
      1,
      call("GET", "/sync", token = owner["accessToken"].asString()).body!!["records"].size(),
    )
    assertEquals(
      400,
      adminCall("PUT", "/api/users/$ownerId/records/workout/$id", body, browser).status,
    )
    assertEquals(400, adminCall("PUT", path, body + mapOf("reason" to " "), browser).status)
  }

  @Test
  fun `admin pagination hides secrets and revokes user and browser sessions`() {
    val browser = administrator()
    val otherAdmin = administrator()
    val ordinary = account("selected@example.com")
    val page = adminCall("GET", "/api/users?limit=1", browser = browser).body!!
    assertEquals(1, page["items"].size())
    assertTrue(page["hasMore"].asBoolean())
    val filtered = adminCall("GET", "/api/users?q=selected", browser = browser).body!!
    assertEquals(1, filtered["items"].size())
    val detail = adminCall("GET", "/api/users/" + ordinary["userId"].asString(), browser = browser)
    listOf("password_hash", "access_hash", "refreshToken", "google_subject", "code_hash").forEach {
      assertFalse(detail.response.body().contains(it))
    }
    assertEquals(400, adminCall("GET", "/api/users?limit=101", browser = browser).status)
    assertEquals(400, adminCall("GET", "/api/records?kind=forged", browser = browser).status)
    val body = mapOf("operationId" to UUID.randomUUID(), "reason" to "Запрошен выход с устройств")
    assertEquals(
      200,
      adminCall(
          "POST",
          "/api/users/" + ordinary["userId"].asString() + "/revoke-sessions",
          body,
          browser,
        )
        .status,
    )
    assertEquals(401, call("GET", "/me", token = ordinary["accessToken"].asString()).status)
    assertEquals(
      200,
      adminCall(
          "POST",
          "/api/users/" + otherAdmin.userId + "/revoke-sessions",
          body + mapOf("operationId" to UUID.randomUUID()),
          browser,
        )
        .status,
    )
    assertEquals(401, adminCall("GET", "/api/users", browser = otherAdmin).status)
    assertEquals(200, adminCall("POST", "/api/logout", emptyMap<String, String>(), browser).status)
    assertEquals(401, adminCall("GET", "/api/users", browser = browser).status)
  }

  @Test
  fun `admin Google login requires an existing explicitly promoted identity`() {
    val google = call("POST", "/auth/google", googleBody("admin-google@example.com")).body!!
    db.update(
      "UPDATE users SET is_admin=true WHERE id=?",
      UUID.fromString(google["userId"].asString()),
    )
    val login = adminCall("POST", "/api/google", googleBody("admin-google@example.com"))
    assertEquals(200, login.status, login.response.body())
    assertTrue(login.response.headers().firstValue("Set-Cookie").isPresent)
    assertEquals(
      403,
      adminCall(
          "POST",
          "/api/google",
          googleBody("ordinary-google@example.com", subject = "ordinary-subject"),
        )
        .status,
    )
  }
}
