package tech.valerochkagym.service.ai

import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class CalendarAiContractTest {
  private val json = JsonMapper.builder().build()

  @Test
  fun `accepted calendar fixture remains byte pinned and contains all executable vectors`() {
    val bytes =
      java.nio.file.Files.readAllBytes(
        java.nio.file.Path.of("src/test/resources/calendar-ai-contract.json")
      )
    val digest =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    assertEquals("42714ea6086c8d7349543cfdb3d11cfac86d04fe67b15ec743ff388f4e31b18e", digest)
    val contract = json.readTree(bytes)
    assertEquals(2, contract["schemaVersion"].asInt())
    assertEquals(
      (1..11).map { "V-%03d".format(it) },
      contract["vectors"].toList().map { it["id"].asString() },
    )
    assertEquals(
      "65254ebf9aaa4062ebf8ef71df99c76876685ce10ec0bd2fa8645fced56ea998",
      contract["fixture"]["plan01"]["sha256"].asString(),
    )
  }

  @Test
  fun `runtime calendar schema preserves provider output tree and its transitive definitions`() {
    val contract =
      json.readTree(
        java.nio.file.Files.readAllBytes(
          java.nio.file.Path.of("src/test/resources/calendar-ai-contract.json")
        )
      )
    val runtime = json.readTree(javaClass.getResourceAsStream("/ai/calendar-output-schema.json"))
    assertEquals("#/${'$'}defs/ProviderOutput", runtime["${'$'}ref"].asString())
    assertEquals(
      setOf("ProviderOutput", "ProviderExercise", "ProviderSet", "Uuid"),
      runtime["${'$'}defs"].properties().map { it.key }.toSet(),
    )
    runtime["${'$'}defs"].properties().forEach { (name, node) ->
      assertEquals(contract["${'$'}defs"][name], node)
    }
    assertTrue(
      runtime["${'$'}defs"]["ProviderOutput"]["properties"]["result"]["properties"]["exercises"][
          "maxItems"]
        .asInt() == 12
    )
  }

  @Test
  fun `calendar provider envelope accepts only the frozen strict shape`() {
    val validator = AiDraftValidator(json)
    val valid =
      json.readTree(
        """{"result":{"name":"Draft","exercises":[{"exerciseId":"11111111-1111-4111-8111-111111111111","restSeconds":0,"plannedSets":[{"reps":8,"durationSec":null}]}]}}"""
      )
    assertEquals(valid, validator.validateCalendar(valid))
    assertTrue(
      runCatching {
          validator.validateCalendar(
            json.readTree(
              """{"result":{"name":"Draft","exercises":[{"exerciseId":"11111111-1111-4111-8111-111111111111","restSeconds":0,"plannedSets":[{"reps":8,"durationSec":null,"weightKg":1}]}]}}"""
            )
          )
        }
        .isFailure
    )
  }
}
