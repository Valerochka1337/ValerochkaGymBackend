package tech.valerochkagym.service.ai

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tech.valerochkagym.config.CoachProviderSettings
import tech.valerochkagym.controller.advice.ApiException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class CoachTurnServiceTest {
  private val json = JsonMapper.builder().build()

  private class Fake : CoachTurnProvider {
    var calls = 0
    var input: CoachTurnInput? = null

    override fun catalog() =
      CoachModelCatalog("AVAILABLE", "coach-default", listOf("coach-default", "coach-other"))

    override fun complete(input: CoachTurnInput): JsonNode {
      calls++
      this.input = input
      return JsonMapper.builder()
        .build()
        .readTree(
          "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\",\"reasoning\":\"PRIVATE\"}}],\"usage\":{}}"
        )
    }
  }

  private fun tools() =
    CoachTurnService.TOOL_NAMES.map {
      mapOf(
        "type" to "function",
        "function" to
          mapOf("name" to it, "description" to "Fixture", "parameters" to mapOf("type" to "object")),
      )
    }

  private fun body(
    messages: List<Map<String, Any?>> =
      listOf(mapOf("role" to "user", "content" to "Добавь подход")),
    model: String? = null,
  ) =
    mapOf(
      "requestId" to UUID.randomUUID().toString(),
      "model" to model,
      "messages" to messages,
      "tools" to tools(),
    )

  private fun bytes(body: Any) = json.writeValueAsBytes(body)

  @Test
  fun `turn uses allowlisted coach model without needing synced workout`() {
    val fake = Fake()
    val service = CoachTurnService(fake, json)
    val request = body(model = "coach-other")
    val result = service.turn(bytes(request))
    assertEquals(request["requestId"], result.requestId)
    assertEquals("coach-other", result.model)
    assertEquals("coach-other", fake.input!!.model)
    assertEquals(1, fake.calls)
    assertFalse(result.completion.toString().contains("PRIVATE"))
    assertFalse(result.completion.has("usage"))
    assertEquals("coach-default", service.turn(bytes(body())).model)
  }

  @Test
  fun `tool result is linked to preceding assistant call`() {
    val fake = Fake()
    val service = CoachTurnService(fake, json)
    val call =
      mapOf(
        "id" to "call-1",
        "type" to "function",
        "function" to mapOf("name" to "get_workout_state", "arguments" to "{}"),
      )
    val messages =
      listOf(
        mapOf("role" to "user", "content" to "Состояние"),
        mapOf("role" to "assistant", "tool_calls" to listOf(call)),
        mapOf("role" to "tool", "tool_call_id" to "call-1", "content" to "{}"),
      )
    service.turn(bytes(body(messages)))
    assertEquals(1, fake.calls)
    assertThrows(ApiException::class.java) { service.turn(bytes(body(messages.dropLast(1)))) }
    assertThrows(ApiException::class.java) { service.turn(bytes(body(listOf(messages.last())))) }
    assertEquals(1, fake.calls)
  }

  @Test
  fun `unknown model fields tools and duplicate JSON never dispatch`() {
    val fake = Fake()
    val service = CoachTurnService(fake, json)
    val valid = bytes(body()).toString(Charsets.UTF_8)
    val invalid =
      listOf(
        bytes(body(model = "forged-model")),
        bytes(body() + mapOf("apiKey" to "secret")),
        bytes(body() + mapOf("tools" to emptyList<Any>())),
        ("{\"requestId\":\"duplicate\"," + valid.drop(1)).toByteArray(),
        (valid + "{}").toByteArray(),
        bytes(body(List(81) { mapOf("role" to "user", "content" to "x") })),
      )
    invalid.forEach {
      assertEquals(400, assertThrows(ApiException::class.java) { service.turn(it) }.status)
    }
    assertEquals(0, fake.calls)
  }

  @Test
  fun `payload byte cap is enforced before provider`() {
    val fake = Fake()
    val service = CoachTurnService(fake, json)
    val valid = bytes(body())
    service.turn(valid + ByteArray(CoachTurnService.MAX_REQUEST_BYTES - valid.size) { 32 })
    assertThrows(ApiException::class.java) {
      service.turn(valid + ByteArray(CoachTurnService.MAX_REQUEST_BYTES + 1 - valid.size) { 32 })
    }
    assertEquals(1, fake.calls)
  }

  @Test
  fun `coach settings default independently and reject unsafe catalog`() {
    val env =
      mapOf(
        "AI_ENABLED" to "true",
        "AI_PROVIDER" to "openai",
        "AI_BASE_URL" to "https://example.test",
        "AI_API_KEY" to "dummy",
        "AI_TEXT_MODEL" to "text",
        "AI_VISION_MODEL" to "vision",
        "AI_COACH_MODEL" to "coach",
        "AI_COACH_MODELS" to "coach,other",
      )
    val settings = CoachProviderSettings.from(env::get)!!
    assertEquals("coach", settings.defaultModel)
    assertEquals(listOf("coach", "other"), settings.models)
    assertEquals("text", settings.provider.textModel)
    assertEquals("vision", settings.provider.visionModel)
    assertNull(CoachProviderSettings.from((env + mapOf("AI_COACH_MODEL" to "bad\nmodel"))::get))
    assertEquals(
      "text",
      CoachProviderSettings.from((env - "AI_COACH_MODEL" - "AI_COACH_MODELS")::get)!!.defaultModel,
    )
  }
}
