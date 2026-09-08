package tech.valerochkagym.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import tech.valerochkagym.controller.advice.ApiError
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.service.admin.AdminService
import tools.jackson.databind.ObjectMapper

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
