package tech.valerochkagym.auth

import java.sql.Timestamp
import java.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Service
import tech.valerochkagym.web.*

data class GoogleAccount(val subject: String, val email: String)

interface GoogleVerifier {
  fun verify(idToken: String, nonce: String): GoogleAccount
}

@Service
class GoogleIdentity(
  private val db: JdbcTemplate,
  private val crypto: Crypto,
  private val clock: Clock,
  private val decoder: JwtDecoder,
  @Value("\${gym.google-client-id}") private val clientId: String,
) : GoogleVerifier {

  fun nonce(): String {
    val nonce = crypto.token()
    db.update(
      "INSERT INTO google_nonces(nonce_hash,expires_at) VALUES (?,?)",
      crypto.hash(nonce),
      Timestamp.from(clock.instant().plusSeconds(300)),
    )
    return nonce
  }

  override fun verify(idToken: String, nonce: String): GoogleAccount {
    if (clientId.isBlank())
      throw ApiException(503, "google_unavailable", "Google-вход пока не настроен")
    if (idToken.length > 8192 || nonce.length > 100) unauthorized()
    val jwt =
      try {
        decoder.decode(idToken)
      } catch (e: org.springframework.security.oauth2.jwt.JwtException) {
        unauthorized()
      }
    if (
      jwt.issuer.toString() !in setOf("https://accounts.google.com", "accounts.google.com") ||
        jwt.audience?.contains(clientId) != true ||
        jwt.getClaimAsString("nonce") != nonce ||
        jwt.getClaimAsBoolean("email_verified") != true ||
        jwt.subject.isNullOrBlank()
    )
      unauthorized()
    if (
      db.update(
        "DELETE FROM google_nonces WHERE nonce_hash=? AND expires_at>?",
        crypto.hash(nonce),
        Timestamp.from(clock.instant()),
      ) != 1
    )
      unauthorized()
    return GoogleAccount(
      jwt.subject ?: unauthorized(),
      jwt.getClaimAsString("email") ?: unauthorized(),
    )
  }
}

@Configuration
class GoogleConfiguration {
  @Bean
  fun googleDecoder(): JwtDecoder =
    NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build()
}
