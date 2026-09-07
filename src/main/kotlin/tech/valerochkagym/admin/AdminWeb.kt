package tech.valerochkagym.admin

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import tech.valerochkagym.auth.*
import tech.valerochkagym.data.*
import tech.valerochkagym.web.*
import tools.jackson.databind.ObjectMapper

const val ADMIN_COOKIE = "__Host-gym-admin"
private val publicAdminApi = setOf("/admin/api/login")

data class AdminCredentials(val username: String, val password: String)

fun adminCookie(request: HttpServletRequest) =
  request.cookies?.singleOrNull { it.name == ADMIN_COOKIE }?.value

class AdminFilter(
  private val admin: AdminService,
  private val limits: RateLimiter,
  private val json: ObjectMapper,
  private val origin: String,
) : OncePerRequestFilter() {
  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    response.setHeader("Cache-Control", "no-store")
    response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
    response.setHeader("Cross-Origin-Opener-Policy", "same-origin")
    response.setHeader(
      "Content-Security-Policy",
      "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    )
    try {
      var bounded = request
      if (request.requestURI.startsWith("/admin/api/")) {
        val mutation = request.method !in setOf("GET", "HEAD", "OPTIONS")
        if (mutation) {
          if (request.getHeader("Origin") != origin)
            throw ApiException(403, "invalid_origin", "Запрос должен исходить из админки")
          if (request.contentType?.substringBefore(';') != "application/json")
            throw ApiException(415, "json_required", "Ожидается JSON")
          val bytes = request.inputStream.readNBytes(262145)
          if (bytes.size > 262144)
            throw ApiException(413, "payload_too_large", "Слишком большой запрос")
          bounded =
            object : HttpServletRequestWrapper(request) {
              override fun getInputStream(): ServletInputStream {
                val stream = ByteArrayInputStream(bytes)
                return object : ServletInputStream() {
                  override fun read() = stream.read()

                  override fun read(b: ByteArray, off: Int, len: Int) = stream.read(b, off, len)

                  override fun isFinished() = stream.available() == 0

                  override fun isReady() = true

                  override fun setReadListener(listener: ReadListener) {
                    throw UnsupportedOperationException()
                  }
                }
              }
            }
        }
        if (request.requestURI in publicAdminApi) {
          limits.check("admin-ip:" + request.remoteAddr, 30)
        } else {
          val token = adminCookie(request) ?: unauthorized()
          val identity = admin.authenticate(token)
          if (
            mutation &&
              !MessageDigest.isEqual(
                admin.csrf(token).toByteArray(),
                (request.getHeader("X-CSRF-Token") ?: "").toByteArray(),
              )
          )
            throw ApiException(403, "invalid_csrf", "Обновите страницу перед повторной попыткой")
          limits.check("admin-user:" + identity.userId, 300)
          SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(identity, null, emptyList())
        }
      }
      chain.doFilter(bounded, response)
    } catch (e: ApiException) {
      response.status = e.status
      response.contentType = "application/json"
      response.characterEncoding = "UTF-8"
      if (e.status == 429) response.setHeader("Retry-After", "60")
      response.writer.write(json.writeValueAsString(ApiError(e.code, e.message)))
    } finally {
      SecurityContextHolder.clearContext()
    }
  }
}

@Configuration
class AdminSecurity {
  @Bean
  @Order(1)
  fun adminChain(
    http: HttpSecurity,
    admin: AdminService,
    limits: RateLimiter,
    json: ObjectMapper,
    @Value("\${gym.admin-origin:https://api.valerochkagym.tech}") origin: String,
  ): SecurityFilterChain =
    http
      .securityMatcher("/admin", "/admin/**")
      // AdminFilter enforces exact Origin and a session-bound synchronizer token for JSON
      // mutations.
      .csrf { it.disable() }
      .cors { it.disable() }
      .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
      .requestCache { it.disable() }
      .formLogin { it.disable() }
      .httpBasic { it.disable() }
      .authorizeHttpRequests {
        it
          .requestMatchers(
            "/admin",
            "/admin/",
            "/admin/index.html",
            "/admin/admin.js",
            "/admin/admin.css",
            *publicAdminApi.toTypedArray(),
          )
          .permitAll()
          .anyRequest()
          .authenticated()
      }
      .addFilterBefore(
        AdminFilter(admin, limits, json, origin),
        UsernamePasswordAuthenticationFilter::class.java,
      )
      .build()
}

@org.springframework.stereotype.Controller
class AdminPageController {
  @GetMapping("/admin", "/admin/") fun index() = "forward:/admin/index.html"
}

@RestController
@RequestMapping("/admin/api")
class AdminController(
  private val admin: AdminService,
  private val auth: AuthService,
  private val limits: RateLimiter,
) {
  private fun cookie(response: HttpServletResponse, value: String, age: Duration) {
    response.addHeader(
      HttpHeaders.SET_COOKIE,
      ResponseCookie.from(ADMIN_COOKIE, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(age)
        .build()
        .toString(),
    )
  }

  @PostMapping("/login")
  fun login(
    @RequestBody body: AdminCredentials,
    response: HttpServletResponse,
  ): Map<String, String> {
    val username = body.username.trim().lowercase()
    if (username.length !in 1..64) unauthorized()
    limits.check("admin-login:" + username, 8)
    val (email, token) = admin.login(username, body.password)
    cookie(response, token, Duration.ofHours(8))
    return mapOf("email" to email, "csrfToken" to admin.csrf(token))
  }

  @GetMapping("/session")
  fun session(@AuthenticationPrincipal identity: Identity, request: HttpServletRequest) =
    mapOf(
      "email" to identity.email,
      "userId" to identity.userId.toString(),
      "csrfToken" to admin.csrf(adminCookie(request)!!),
    )

  @PostMapping("/logout")
  fun logout(@AuthenticationPrincipal identity: Identity, response: HttpServletResponse) {
    auth.logout(identity)
    cookie(response, "", Duration.ZERO)
  }

  @GetMapping("/summary") fun summary() = admin.summary()

  @GetMapping("/users")
  fun users(
    @RequestParam(defaultValue = "") q: String,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "50") limit: Int,
  ) = admin.users(q, offset, limit)

  @GetMapping("/users/{id}") fun user(@PathVariable id: UUID) = admin.user(id)

  @PostMapping("/users/{id}/revoke-sessions")
  fun revoke(
    @AuthenticationPrincipal actor: Identity,
    @PathVariable id: UUID,
    @RequestBody body: AdminAction,
  ) = admin.revoke(actor, id, body)

  @GetMapping("/records")
  fun records(
    @RequestParam kind: String,
    @RequestParam(required = false) userId: UUID?,
    @RequestParam(defaultValue = "") q: String,
    @RequestParam(defaultValue = "false") deleted: Boolean,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "50") limit: Int,
  ) = admin.records(kind, userId, q, deleted, offset, limit)

  @GetMapping("/users/{user}/records/{kind}/{id}")
  fun record(@PathVariable user: UUID, @PathVariable kind: String, @PathVariable id: UUID) =
    admin.record(user, kind, id)

  @PutMapping("/users/{user}/records/{kind}/{id}")
  fun edit(
    @AuthenticationPrincipal actor: Identity,
    @PathVariable user: UUID,
    @PathVariable kind: String,
    @PathVariable id: UUID,
    @RequestBody body: AdminEdit,
  ) = admin.edit(actor, user, kind, id, body)

  @GetMapping("/audit")
  fun audit(
    @RequestParam(required = false) userId: UUID?,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "50") limit: Int,
  ) = admin.audit(userId, offset, limit)

  @GetMapping("/audit/{id}") fun auditEntry(@PathVariable id: Long) = admin.auditEntry(id)

  @GetMapping("/users/{id}/exercise-options")
  fun exerciseOptions(@PathVariable id: UUID) = admin.exerciseOptions(id)

  @GetMapping("/catalog")
  fun catalog() =
    mapOf("equipment" to EquipmentDefinitions.provides.keys, "muscles" to RecordValidator.muscles)
}
