package tech.valerochkagym.service.auth

import java.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.stereotype.Service
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.repository.auth.NonceRepository
import tech.valerochkagym.repository.model.NonceEntity
import tech.valerochkagym.service.model.GoogleAccount
import tech.valerochkagym.utils.Crypto

interface GoogleVerifier {
  fun verify(idToken: String, nonce: String): GoogleAccount
}

@Service
class GoogleIdentity(
  private val nonces: NonceRepository,
  private val crypto: Crypto,
  private val clock: Clock,
  private val decoder: JwtDecoder,
  @Value("\${gym.google-client-id}") private val clientId: String,
) : GoogleVerifier {

  fun nonce(): String {
    val nonce = crypto.token()
    nonces.save(NonceEntity(crypto.hash(nonce), clock.instant().plusSeconds(300)))
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
    if (nonces.consume(crypto.hash(nonce), clock.instant()) != 1) unauthorized()
    return GoogleAccount(
      jwt.subject ?: unauthorized(),
      jwt.getClaimAsString("email") ?: unauthorized(),
    )
  }
}
