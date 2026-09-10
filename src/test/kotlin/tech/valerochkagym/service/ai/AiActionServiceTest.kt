package tech.valerochkagym.service.ai

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tech.valerochkagym.config.AiProviderSettings
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.model.AiImage
import tools.jackson.databind.json.JsonMapper

class AiActionServiceTest {
  private val json = JsonMapper.builder().build()
  private val validator = AiDraftValidator(json)

  @Test
  fun `configuration requires explicit HTTPS provider and both model IDs`() {
    val good =
      mapOf(
        "AI_ENABLED" to "true",
        "AI_PROVIDER" to "openai",
        "AI_BASE_URL" to "https://provider.example/v1",
        "AI_API_KEY" to "dummy-key",
        "AI_TEXT_MODEL" to "test-text",
        "AI_VISION_MODEL" to "test-vision",
      )
    assertNotNull(AiProviderSettings.from(good::get))
    assertNull(AiProviderSettings.from(emptyMap<String, String>()::get))
    for (patch in
      listOf(
        mapOf("AI_TEXT_MODEL" to ""),
        mapOf("AI_VISION_MODEL" to ""),
        mapOf("AI_PROVIDER" to "other"),
        mapOf("AI_BASE_URL" to "http://localhost"),
        mapOf("AI_BASE_URL" to "https://user:pass@example.com"),
        mapOf("AI_BASE_URL" to "https://example.com?key=x"),
        mapOf("AI_BASE_URL" to "https://example.com/other"),
        mapOf("AI_API_KEY" to "secret\nvalue"),
      )) assertNull(AiProviderSettings.from((good + patch)::get))
    assertFalse(AiProviderSettings.from(good::get).toString().contains("dummy-key"))
  }

  @Test
  fun `exercise validation refuses malformed duplicate unknown and empty results`() {
    for (raw in
      listOf(
        "{}",
        """{"result":{"kind":"NEW","name":"x","type":"STRENGTH","muscles":[]}}""",
        """{"result":{"kind":"NEW","name":"x","type":"STRENGTH","muscles":[{"muscle":"QUADS","contribution":0}]}}""",
        """{"result":{"kind":"NEW","name":"x","type":"STRENGTH","muscles":[{"muscle":"QUADS","contribution":100},{"muscle":"QUADS","contribution":50}]}}""",
        """{"result":{"kind":"NEW","name":"x","type":"UNKNOWN","muscles":[{"muscle":"QUADS","contribution":100}]}}""",
        """{"result":{"kind":"EXISTING","exerciseId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}}""",
      )) assertEquals(
      "ai_invalid_response",
      assertThrows(ApiException::class.java) {
          validator.validate(json.readTree(raw), false, emptySet())
        }
        .code,
    )
  }

  private fun jpeg(width: Int, height: Int) =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", it) }
      .toByteArray()

  @Test
  fun `JPEG dimensions bytes base64 boundaries and MIME are enforced without cache files`() {
    val images = AiImageInput()
    fun image(bytes: ByteArray) = AiImage("image/jpeg", Base64.getEncoder().encodeToString(bytes))
    images.validate(image(jpeg(3072, 1)))
    assertEquals(
      "invalid_image",
      assertThrows(ApiException::class.java) { images.validate(image(jpeg(3073, 1))) }.code,
    )
    val max = jpeg(1, 1).copyOf(6 * 1024 * 1024)
    images.validate(image(max))
    assertEquals(
      "payload_too_large",
      assertThrows(ApiException::class.java) { images.validate(image(max.copyOf(max.size + 1))) }
        .code,
    )
    assertEquals(
      "payload_too_large",
      assertThrows(ApiException::class.java) {
          images.validate(AiImage("image/jpeg", "A".repeat(8 * 1024 * 1024 + 1)))
        }
        .code,
    )
    assertEquals(
      "invalid_image",
      assertThrows(ApiException::class.java) {
          images.validate(AiImage("image/png", Base64.getEncoder().encodeToString(jpeg(1, 1))))
        }
        .code,
    )
    assertEquals(
      "invalid_image",
      assertThrows(ApiException::class.java) { images.validate(AiImage("image/jpeg", "bad!")) }.code,
    )
  }

  @Test
  fun `InBody requires exact nullable fields finite factual values and valid calendar dates`() {
    val props = validator.schema(true)["properties"]["result"]["properties"]["draft"]["properties"]
    val draft =
      props
        .properties()
        .associate {
          it.key to
            if (it.key == "segments")
              it.value["properties"].properties().associate { segment ->
                segment.key to
                  mapOf(
                    "leanMassKg" to null,
                    "leanPercentage" to null,
                    "fatMassKg" to null,
                    "fatPercentage" to null,
                  )
              }
            else null
        }
        .toMutableMap<String, Any?>()
    fun raw(values: Map<String, Any?>) =
      json.valueToTree<tools.jackson.databind.JsonNode>(
        mapOf("result" to mapOf("kind" to "INBODY", "draft" to values))
      )
    assertThrows(ApiException::class.java) { validator.validate(raw(draft), true, emptySet()) }
    draft["weightKg"] = 70.0
    assertEquals(
      70.0,
      validator.validate(raw(draft), true, emptySet())["draft"]["weightKg"].asDouble(),
    )
    for (changed in
      listOf(
        draft + ("weightKg" to -1),
        draft + ("weightKg" to "70kg"),
        draft + ("bodyFatPercentage" to 101),
        draft + ("measuredDate" to "2026-02-30"),
        draft + ("measuredTime" to "25:00"),
        draft + ("visceralFatLevel" to 1.5),
        draft + ("patientName" to "private"),
        draft - "measuredDate",
      )) {
      assertThrows(ApiException::class.java) { validator.validate(raw(changed), true, emptySet()) }
    }
    val nonfinite = json.readTree(json.writeValueAsString(raw(draft)).replace("70.0", "1e999"))
    assertThrows(ApiException::class.java) { validator.validate(nonfinite, true, emptySet()) }
  }
}
