package tech.valerochkagym.service.health

import java.time.*
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.utils.Crypto

class HealthCursorCodecTest {
  @Test
  fun `page expires at 24 hours while committed cursor survives and purposes and keys differ`() {
    val owner = UUID.randomUUID()
    val now = Instant.parse("2030-01-01T00:00:00Z")
    val crypto = Crypto("test-pepper-with-at-least-thirty-two-bytes")
    fun codec(seconds: Long) =
      HealthCursorCodec(crypto, Clock.fixed(now.plusSeconds(seconds), ZoneOffset.UTC))
    val start = codec(0)
    val state = start.fresh("snapshot", 0, 14).copy(last = 4)
    val page = start.encode(owner, state)
    val committed = start.encode(owner, state.copy(last = 14, expires = 0), true)
    assertEquals(14, codec(86399).decode(owner, page).h)
    assertThrows(ApiException::class.java) { codec(86400).decode(owner, page) }
    assertEquals(14, codec(86400 * 365L).decode(owner, committed, true).h)
    assertThrows(ApiException::class.java) { start.decode(owner, page, true) }
    assertThrows(ApiException::class.java) { start.decode(owner, committed) }
    assertThrows(ApiException::class.java) { start.decode(UUID.randomUUID(), page) }
    val rotated =
      HealthCursorCodec(
        Crypto("another-pepper-with-at-least-thirty-two-bytes"),
        Clock.fixed(now, ZoneOffset.UTC),
      )
    assertThrows(ApiException::class.java) { rotated.decode(owner, committed, true) }
  }
}
