package tech.valerochkagym.service.ai

import java.util.Base64
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import tech.valerochkagym.controller.advice.ApiException

class AiKeyEncryptionTest {
  private fun crypto(byte: Byte = 1) =
    AiKeyEncryption(
      MockEnvironment()
        .withProperty(
          "AI_SETTINGS_ENCRYPTION_KEY",
          Base64.getEncoder().encodeToString(ByteArray(32) { byte }),
        )
    )

  @Test
  fun `encryption is randomized authenticated and survives reconstruction`() {
    val first = crypto().encrypt("secret-api-key")
    val second = crypto().encrypt("secret-api-key")
    assertNotEquals(first, second)
    assertFalse(first.contains("secret-api-key"))
    assertEquals("secret-api-key", crypto().decrypt(first))
    assertThrows(ApiException::class.java) { crypto(2).decrypt(first) }
    val bytes = Base64.getDecoder().decode(first.removePrefix("v1:"))
    bytes[15] = (bytes[15].toInt() xor 1).toByte()
    assertThrows(ApiException::class.java) {
      crypto().decrypt("v1:" + Base64.getEncoder().encodeToString(bytes))
    }
    assertThrows(ApiException::class.java) { crypto().decrypt("v2:" + first.substring(3)) }
  }

  @Test
  fun `missing or invalid master key never permits plaintext storage`() {
    val missing = AiKeyEncryption(MockEnvironment())
    assertFalse(missing.available)
    assertThrows(ApiException::class.java) { missing.encrypt("secret") }
    assertThrows(IllegalArgumentException::class.java) {
      AiKeyEncryption(MockEnvironment().withProperty("AI_SETTINGS_ENCRYPTION_KEY", "c2hvcnQ="))
    }
  }
}
