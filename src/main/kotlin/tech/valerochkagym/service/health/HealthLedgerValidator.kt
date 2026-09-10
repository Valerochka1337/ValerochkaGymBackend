package tech.valerochkagym.service.health

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.bad
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class HealthLedgerValidator(private val json: ObjectMapper) {
  fun fields(n: JsonNode, vararg keys: String) {
    if (!n.isObject || n.propertyNames().toSet() != keys.toSet()) bad("Некорректные поля")
  }

  fun uuid(n: JsonNode): String {
    if (
      !n.isString ||
        !Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
          .matches(n.asString()) ||
        !runCatching { UUID.fromString(n.asString()).toString() == n.asString() }
          .getOrDefault(false)
    )
      bad("Некорректный UUID")
    return n.asString()
  }

  fun number(n: JsonNode): Long {
    if (!n.isIntegralNumber || !n.canConvertToLong() || n.asLong() < 0)
      bad("Некорректное целое число")
    return n.asLong()
  }

  private fun text(n: JsonNode, max: Int, min: Int = 0, nullable: Boolean = false) {
    if (nullable && n.isNull) return
    if (!n.isString || n.asString().length !in min..max) bad("Некорректная строка")
    val s = n.asString()
    var i = 0
    while (i < s.length) {
      if (s[i].isHighSurrogate()) {
        if (i + 1 >= s.length || !s[++i].isLowSurrogate()) bad("Некорректный Unicode")
      } else if (s[i].isLowSurrogate()) bad("Некорректный Unicode")
      i++
    }
  }

  private fun observed(p: JsonNode) {
    text(p["observedAt"], 40, 10)
    val value = p["observedAt"].asString()
    val valid =
      runCatching {
          when (p["observedPrecision"].asString()) {
            "DATE" -> LocalDate.parse(value).toString() == value
            "DATETIME" -> {
              require(
                Regex(
                    "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})"
                  )
                  .matches(value)
              )
              OffsetDateTime.parse(value)
              true
            }
            else -> false
          }
        }
        .getOrDefault(false)
    if (!valid) bad("Некорректная дата")
  }

  fun version(v: JsonNode): JsonNode {
    fields(
      v,
      "versionId",
      "logicalId",
      "parentVersionId",
      "kind",
      "state",
      "enteredAtEpochMs",
      "payload",
    )
    uuid(v["versionId"])
    uuid(v["logicalId"])
    if (!v["parentVersionId"].isNull) uuid(v["parentVersionId"])
    number(v["enteredAtEpochMs"])
    val kind = v["kind"].asString()
    val state = v["state"].asString()
    val p = v["payload"]
    if (
      kind !in setOf("health_report", "health_observation", "health_restriction") ||
        state !in setOf("CONFIRMED", "TOMBSTONE")
    )
      bad("Некорректный тип")
    if (state == "TOMBSTONE") {
      if (!p.isNull || v["parentVersionId"].isNull) bad("Некорректное удаление")
    } else
      when (kind) {
        "health_report" -> {
          fields(p, "title", "sourceText", "observedAt", "observedPrecision")
          text(p["title"], 200, 1)
          if (p["title"].asString().trim() != p["title"].asString()) bad("Некорректный заголовок")
          text(p["sourceText"], 10000, nullable = true)
          observed(p)
        }
        "health_restriction" -> {
          fields(p, "textOriginal", "confirmedAtEpochMs")
          text(p["textOriginal"], 5000, 1)
          number(p["confirmedAtEpochMs"])
        }
        "health_observation" -> {
          fields(p, *observationKeys)
          uuid(p["reportLogicalId"])
          uuid(p["metricIdentityId"])
          text(p["metricNameOriginal"], 200, 1)
          text(p["valueOriginal"], 4096, 1)
          for (k in
            listOf("unitOriginal", "methodOriginal", "specimenOriginal", "sourceOriginal")) text(
            p[k],
            1000,
            1,
            nullable = true,
          )
          text(p["referenceOriginal"], 4096, 1, nullable = true)
          observed(p)
          if (number(p["enteredAtEpochMs"]) != v["enteredAtEpochMs"].asLong())
            bad("Некорректное время")
          for (k in listOf("numberValue", "rangeLow", "rangeHigh")) if (!p[k].isNull) {
            if (
              !p[k].isString ||
                !Regex("^(?!-0$)-?(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{0,11}[1-9])?$")
                  .matches(p[k].asString())
            )
              bad("Некорректное десятичное число")
          }
          val n = !p["numberValue"].isNull
          val lo = !p["rangeLow"].isNull
          val hi = !p["rangeHigh"].isNull
          val op = !p["operator"].isNull
          val valid =
            when (p["valueKind"].asString()) {
              "NUMBER" -> n && !lo && !hi && !op
              "COMPARATOR" ->
                n && !lo && !hi && p["operator"].asString() in setOf("LT", "LE", "GT", "GE", "EQ")
              "RANGE" ->
                !n &&
                  lo &&
                  hi &&
                  !op &&
                  p["rangeLow"].asString().toBigDecimal() <=
                    p["rangeHigh"].asString().toBigDecimal()
              "CATEGORY",
              "TEXT" -> !n && !lo && !hi && !op
              else -> false
            }
          if (!valid) bad("Некорректное значение")
        }
      }
    val out = json.createObjectNode()
    for (k in versionKeys) out.set(
      k,
      if (k == "payload" && !p.isNull) {
        val payload = json.createObjectNode()
        val keys =
          when (kind) {
            "health_report" -> arrayOf("title", "sourceText", "observedAt", "observedPrecision")
            "health_restriction" -> arrayOf("textOriginal", "confirmedAtEpochMs")
            else -> observationKeys
          }
        for (key in keys) payload.set(key, p[key])
        payload
      } else v[k],
    )
    return out
  }

  fun operation(n: JsonNode): JsonNode {
    fields(n, "operationId", "versions", "heads")
    uuid(n["operationId"])
    for (k in listOf("versions", "heads")) if (!n[k].isArray || n[k].size() > 500)
      bad("Некорректный список")
    if (n["versions"].size() + n["heads"].size() == 0) bad("Пустая операция")
    val versions = mutableSetOf<String>()
    val heads = mutableSetOf<String>()
    n["versions"].forEach {
      version(it)
      if (!versions.add(uuid(it["versionId"]))) bad("Повтор версии")
    }
    n["heads"].forEach {
      fields(it, "logicalId", "currentVersionId", "baseHeadRevision")
      if (!heads.add(uuid(it["logicalId"]))) bad("Повтор головы")
      uuid(it["currentVersionId"])
      number(it["baseHeadRevision"])
    }
    return n
  }

  fun disclosure(n: JsonNode): JsonNode {
    fields(n, "operationId", "baseRevision", "noticeVersion", "enabled")
    uuid(n["operationId"])
    number(n["baseRevision"])
    if (number(n["noticeVersion"]) !in 1..Int.MAX_VALUE.toLong() || !n["enabled"].isBoolean)
      bad("Некорректное согласие")
    return n
  }

  companion object {
    val versionKeys =
      arrayOf(
        "versionId",
        "logicalId",
        "parentVersionId",
        "kind",
        "state",
        "enteredAtEpochMs",
        "payload",
      )
    val observationKeys =
      arrayOf(
        "reportLogicalId",
        "metricIdentityId",
        "metricNameOriginal",
        "valueKind",
        "valueOriginal",
        "numberValue",
        "rangeLow",
        "rangeHigh",
        "operator",
        "unitOriginal",
        "methodOriginal",
        "specimenOriginal",
        "sourceOriginal",
        "referenceOriginal",
        "observedAt",
        "observedPrecision",
        "enteredAtEpochMs",
      )
  }
}
