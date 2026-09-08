package tech.valerochkagym.security

import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.repository.auth.PostgresAuthRepository
import tech.valerochkagym.utils.Crypto

@Component
class RateLimiter(private val postgres: PostgresAuthRepository, private val crypto: Crypto) {
  fun check(bucket: String, limit: Int = 30) {
    val count = postgres.rate(crypto.hash(bucket))
    if (count > limit)
      throw ApiException(429, "rate_limited", "Слишком много попыток. Подождите минуту")
  }
}
