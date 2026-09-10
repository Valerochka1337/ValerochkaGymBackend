package tech.valerochkagym.service.health

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class HealthLedgerValidatorTest {
  private val json = JsonMapper.builder().build()
  private val validator = HealthLedgerValidator(json)

  @Test
  fun `canonical fixture validates and hashes identically`() {
    val fixture = json.readTree(javaClass.getResourceAsStream("/manual-health-contract.json"))
    for (vector in fixture["canonicalOperationRequest"]["vectors"]) {
      val bytes = vector["utf8"].asString().toByteArray()
      val raw = HealthRawBodyReader(json)
      assertEquals(vector["sha256"].asString(), raw.sha256(bytes))
      val n = validator.operation(raw.tree(bytes))
      n["versions"].forEach { assertEquals(it, validator.version(it)) }
    }
  }

  @Test
  fun `fixture schema negatives reject without normalizing originals`() {
    val f = json.readTree(javaClass.getResourceAsStream("/manual-health-contract.json"))
    for (test in f["invalidFixtures"].filter { it.has("jsonPatch") }) {
      val base =
        if (test.has("baseFixture"))
          f["validFixtures"]
            .first { it["name"].asString() == test["baseFixture"].asString() }["value"]
        else {
          val vector =
            f["canonicalOperationRequest"]["vectors"].first {
              it["name"].asString() == test["baseCanonicalVector"].asString()
            }
          json.readTree(vector["utf8"].asString()).at(test["valuePointer"].asString())
        }
      val changed =
        json.readTree(json.writeValueAsBytes(base)) as tools.jackson.databind.node.ObjectNode
      for (patch in test["jsonPatch"]) changed.set(
        patch["path"].asString().removePrefix("/"),
        patch["value"],
      )
      val v =
        if (test["schema"].asString().endsWith("ObservationPayload")) {
          json.valueToTree<tools.jackson.databind.JsonNode>(
            linkedMapOf(
              "versionId" to "22222222-2222-4222-8222-222222222223",
              "logicalId" to "11111111-1111-4111-8111-111111111112",
              "parentVersionId" to null,
              "kind" to "health_observation",
              "state" to "CONFIRMED",
              "enteredAtEpochMs" to changed["enteredAtEpochMs"].asLong(),
              "payload" to changed,
            )
          )
        } else changed
      assertThrows(
        tech.valerochkagym.controller.advice.ApiException::class.java,
        { validator.version(v) },
        test["name"].asString(),
      )
    }
  }

  @Test
  fun `missing fields wrong UUID variant empty nullable originals and oversize reject`() {
    val f = json.readTree(javaClass.getResourceAsStream("/manual-health-contract.json"))
    val original = json.readTree(f["canonicalOperationRequest"]["vectors"][0]["utf8"].asString())
    val v = original["versions"][0]
    for (key in HealthLedgerValidator.versionKeys) {
      val bad = json.readTree(json.writeValueAsBytes(v)) as tools.jackson.databind.node.ObjectNode
      bad.remove(key)
      assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
        validator.version(bad)
      }
    }
    val wrong = json.readTree(json.writeValueAsBytes(v)) as tools.jackson.databind.node.ObjectNode
    wrong.put("versionId", "00000000-0000-0000-0000-000000000000")
    assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
      validator.version(wrong)
    }
  }
}
