package tech.valerochkagym.service.coachrelation

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.ApiException

@Component
class CoachRelationCrypto(env: Environment, private val clock: Clock) {
  val current = env.getProperty("gym.coach-relations.key-version", Int::class.java, 1)

  private data class Key(val pepper: ByteArray, val retireAt: Long)

  private val keys: Map<Int, Key> =
    buildMap {
        put(current, Key(env.getRequiredProperty("gym.token-pepper").toByteArray(), Long.MAX_VALUE))
        env
          .getProperty("gym.coach-relations.retained-key-versions", "")
          .split(',')
          .filter(String::isNotBlank)
          .forEach {
            val v = it.trim().toInt()
            require(v != current && v > 0)
            put(
              v,
              Key(
                env.getRequiredProperty("gym.coach-relations.keys.$v.pepper").toByteArray(),
                env.getRequiredProperty("gym.coach-relations.keys.$v.retire-at-millis").toLong(),
              ),
            )
          }
      }
      .also { require(current > 0 && it.values.all { k -> k.pepper.size >= 32 }) }

  fun versions() = keys.filterValues { it.retireAt > clock.millis() }.keys

  fun token() = encode(ByteArray(32).also(SecureRandom()::nextBytes))

  fun validToken(token: String): Boolean =
    try {
      token.length == 43 && encode(Base64.getUrlDecoder().decode(token)) == token
    } catch (_: Exception) {
      false
    }

  fun hmac(purpose: String, version: Int, message: String): String {
    val key =
      keys[version]?.takeIf { it.retireAt > clock.millis() }
        ?: throw ApiException(410, "cursor_key_retired", "Ключ курсора отозван")
    val derived = mac(key.pepper, "yarumo/coach-relations/$purpose/key/v$version")
    return mac(derived, message).joinToString("") { "%02x".format(it) }
  }

  fun cursor(fields: Map<String, String>): String {
    val all = linkedMapOf("v" to "1", "k" to current.toString()).apply { putAll(fields) }
    val query = all.entries.joinToString("&") { "${it.key}=${it.value}" }
    return encode(query.toByteArray()) + "." + hmac("cursor", current, query)
  }

  fun decode(raw: String): Map<String, String> {
    try {
      require(raw.length in 1..4096)
      val parts = raw.split('.')
      require(parts.size == 2)
      val bytes = Base64.getUrlDecoder().decode(parts[0])
      require(encode(bytes) == parts[0])
      val body = bytes.toString(Charsets.UTF_8)
      val pairs =
        body.split('&').map { it.split('=', limit = 2).also { p -> require(p.size == 2) } }
      val fields = pairs.associate { it[0] to it[1] }
      require(fields.size == pairs.size && fields["v"] == "1")
      val version = fields.getValue("k").toInt()
      require(
        MessageDigest.isEqual(hmac("cursor", version, body).toByteArray(), parts[1].toByteArray())
      )
      if (fields.getValue("expiresAtMillis").toLong() <= clock.millis())
        throw ApiException(410, "cursor_expired", "Курсор истёк")
      return fields
    } catch (e: ApiException) {
      throw e
    } catch (_: Exception) {
      throw ApiException(400, "invalid_cursor", "Некорректный курсор")
    }
  }

  private fun mac(key: ByteArray, text: String) =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(key, "HmacSHA256"))
      doFinal(text.toByteArray(Charsets.UTF_8))
    }

  private fun encode(bytes: ByteArray) =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
