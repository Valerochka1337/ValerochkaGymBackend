package tech.valerochkagym.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.service.auth.AuthService

class BearerFilter(
  private val auth: AuthService,
  private val limits: RateLimiter,
  private val catalog: tech.valerochkagym.repository.catalog.CatalogStateRepository,
) : OncePerRequestFilter() {
  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    try {
      if (request.contentLengthLong > 10 * 1024 * 1024)
        throw ApiException(413, "payload_too_large", "Превышен размер запроса")
      if (request.requestURI.startsWith("/v1/auth/")) limits.check("ip:${request.remoteAddr}", 120)
      val header = request.getHeader("Authorization")
      if (header != null) {
        if (!header.startsWith("Bearer ")) unauthorized()
        val identity = auth.authenticate(header.removePrefix("Bearer ")) ?: unauthorized()
        SecurityContextHolder.getContext().authentication =
          UsernamePasswordAuthenticationToken(identity, null, emptyList())
        limits.check("user:${identity.userId}", 300)
      }
      if (
        (request.requestURI.startsWith("/v1/sync") ||
          request.requestURI.startsWith("/v1/records")) &&
          catalog.findById(1).orElseThrow().active &&
          request.getHeader("X-Gym-Sync-Version") != "2"
      )
        throw ApiException(426, "client_update_required", "Обновите приложение для общего каталога")
      val bounded =
        object : HttpServletRequestWrapper(request) {
          override fun getInputStream(): ServletInputStream {
            val delegate = request.inputStream
            return object : ServletInputStream() {
              private var count = 0

              override fun read(): Int {
                val value = delegate.read()
                if (value >= 0 && ++count > 10 * 1024 * 1024)
                  throw ApiException(413, "payload_too_large", "Превышен размер запроса")
                return value
              }

              override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val read = delegate.read(bytes, offset, length)
                if (read > 0) count += read
                if (count > 10 * 1024 * 1024)
                  throw ApiException(413, "payload_too_large", "Превышен размер запроса")
                return read
              }

              override fun isFinished() = delegate.isFinished

              override fun isReady() = delegate.isReady

              override fun setReadListener(listener: ReadListener) =
                delegate.setReadListener(listener)
            }
          }
        }
      chain.doFilter(bounded, response)
    } catch (e: ApiException) {
      response.status = e.status
      response.contentType = "application/json"
      response.characterEncoding = "UTF-8"
      if (e.status == 429) response.setHeader("Retry-After", "60")
      response.writer.write("{\"code\":\"${e.code}\",\"message\":\"${e.message}\"}")
    } finally {
      SecurityContextHolder.clearContext()
    }
  }
}
