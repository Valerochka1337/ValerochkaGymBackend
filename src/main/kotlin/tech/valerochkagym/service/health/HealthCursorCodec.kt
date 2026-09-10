package tech.valerochkagym.service.health

import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.UUID
import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.utils.Crypto

data class HealthCursor(
  val mode: String,
  val c: Long,
  val h: Long,
  val last: Long,
  val expires: Long,
)

@Component
class HealthCursorCodec(private val crypto: Crypto, private val clock: Clock) {
  fun encode(owner: UUID, c: HealthCursor, committed: Boolean = false): String {
    val body =
      listOf(
          if (committed) "health-commit-v1" else "health-page-v1",
          owner,
          c.mode,
          c.c,
          c.h,
          c.last,
          c.expires,
        )
        .joinToString("|")
    val encoded =
      Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray(Charsets.UTF_8))
    return "$encoded.${crypto.hash(body)}"
  }

  fun decode(owner: UUID, token: String, committed: Boolean = false): HealthCursor =
    try {
      require(token.toByteArray().size in 1..4096)
      val parts = token.split('.')
      require(parts.size == 2)
      val body = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
      require(MessageDigest.isEqual(crypto.hash(body).toByteArray(), parts[1].toByteArray()))
      val p = body.split('|')
      require(p.size == 7 && p[0] == if (committed) "health-commit-v1" else "health-page-v1")
      require(p[1] == owner.toString())
      val c = HealthCursor(p[2], p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong())
      require(c.c >= 0 && c.h >= c.c && c.last in 0..c.h)
      if (!committed) require(c.expires > clock.millis())
      c
    } catch (_: Exception) {
      expired()
    }

  fun fresh(mode: String, c: Long, h: Long) = HealthCursor(mode, c, h, 0, clock.millis() + 86400000)

  fun expired(): Nothing =
    throw ApiException(410, "health_cursor_expired", "Обновите медицинские записи")
}
