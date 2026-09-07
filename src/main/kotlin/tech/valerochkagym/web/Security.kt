package tech.valerochkagym.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tech.valerochkagym.auth.AuthService
import tech.valerochkagym.auth.Crypto

@Component
class RateLimiter(private val db: JdbcTemplate, private val crypto: Crypto) {
  fun check(bucket: String, limit: Int = 30) {
    val count =
      db.queryForObject(
        """INSERT INTO rate_limits(bucket,window_start,count) VALUES (?,date_trunc('minute',now()),1)
            ON CONFLICT(bucket) DO UPDATE SET window_start=date_trunc('minute',now()),
            count=CASE WHEN rate_limits.window_start=date_trunc('minute',now()) THEN rate_limits.count+1 ELSE 1 END RETURNING count""",
        Int::class.java,
        crypto.hash(bucket),
      )!!
    if (count > limit)
      throw ApiException(429, "rate_limited", "Слишком много попыток. Подождите минуту")
  }
}

class BearerFilter(private val auth: AuthService, private val limits: RateLimiter) :
  OncePerRequestFilter() {
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

@Configuration
class Security {
  @Bean
  fun filterChain(http: HttpSecurity, auth: AuthService, limits: RateLimiter): SecurityFilterChain =
    http
      .csrf { it.disable() }
      .cors { it.disable() }
      .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
      .requestCache { it.disable() }
      .formLogin { it.disable() }
      .httpBasic { it.disable() }
      .authorizeHttpRequests {
        it
          .requestMatchers("/v1/auth/**", "/health", "/actuator/health/**")
          .permitAll()
          .anyRequest()
          .authenticated()
      }
      .exceptionHandling {
        it.authenticationEntryPoint { _, response, _ ->
          response.status = 401
          response.contentType = "application/json"
          response.writer.write(
            "{\"code\":\"unauthorized\",\"message\":\"Authentication required\"}"
          )
        }
      }
      .addFilterBefore(BearerFilter(auth, limits), UsernamePasswordAuthenticationFilter::class.java)
      .build()
}
