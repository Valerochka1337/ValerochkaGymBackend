package tech.valerochkagym.service.data

import java.util.UUID
import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.Record
import tech.valerochkagym.repository.catalog.EquipmentRepository
import tech.valerochkagym.service.model.RecordKey
import tools.jackson.databind.JsonNode

/** The wire format contains domain aggregates with UUID references, never local SQLite IDs. */
@Component
class RecordValidator(
  private val equipmentRows: tech.valerochkagym.repository.catalog.EquipmentRepository,
  private val json: tools.jackson.databind.ObjectMapper,
) {
  private fun coverage() =
    equipmentRows.findAll().associate {
      it.id to json.readTree(it.payload)["provides"].toList().map { n -> n.asString() }.toSet()
    }

  companion object {
    val kinds = setOf("exercise", "gym", "routine", "workout", "measurement", "schedule")
    val measurementFields =
      setOf(
        "measuredAt",
        "weightKg",
        "skeletalMuscleMassKg",
        "bodyFatPercentage",
        "bodyFatMassKg",
        "visceralFatLevel",
        "waistHipRatio",
        "inBodyScore",
        "totalBodyWaterLiters",
        "proteinKg",
        "mineralsKg",
        "bodyMassIndex",
        "fatFreeMassKg",
        "basalMetabolicRateKcal",
        "recommendedCalorieIntakeKcal",
        "leftArmLeanMassKg",
        "leftArmLeanPercentage",
        "rightArmLeanMassKg",
        "rightArmLeanPercentage",
        "trunkLeanMassKg",
        "trunkLeanPercentage",
        "leftLegLeanMassKg",
        "leftLegLeanPercentage",
        "rightLegLeanMassKg",
        "rightLegLeanPercentage",
        "leftArmFatMassKg",
        "leftArmFatPercentage",
        "rightArmFatMassKg",
        "rightArmFatPercentage",
        "trunkFatMassKg",
        "trunkFatPercentage",
        "leftLegFatMassKg",
        "leftLegFatPercentage",
        "rightLegFatMassKg",
        "rightLegFatPercentage",
        "waistCm",
        "chestCm",
        "hipsCm",
        "rightRelaxedArmCm",
        "rightThighCm",
      )
    val setFields = setOf("weightKg", "reps", "durationSec", "speedKmh", "inclinePct")
    val muscles =
      setOf(
        "UPPER_CHEST",
        "LOWER_CHEST",
        "FRONT_DELTS",
        "SIDE_DELTS",
        "REAR_DELTS",
        "ROTATOR_CUFF",
        "SERRATUS_ANTERIOR",
        "BICEPS",
        "TRICEPS",
        "FOREARMS",
        "ABS",
        "OBLIQUES",
        "HIP_FLEXORS",
        "ADDUCTORS",
        "QUADS",
        "TIBIALIS_ANTERIOR",
        "CALVES",
        "HAMSTRINGS",
        "GLUTES",
        "HIP_ABDUCTORS",
        "LOWER_BACK",
        "LATS",
        "UPPER_BACK",
        "TRAPS",
        "NECK",
      )
  }

  private fun shape(n: JsonNode, fields: Set<String>) {
    if (!n.isObject || n.properties().any { it.key !in fields }) bad("Неизвестные поля объекта")
  }

  private fun text(n: JsonNode, key: String, max: Int = 200, blank: Boolean = false): String {
    val value = n.get(key) ?: bad("Отсутствует $key")
    if (!value.isString || value.asString().length > max || (!blank && value.asString().isBlank()))
      bad("Некорректное поле $key")
    return value.asString()
  }

  private fun enum(n: JsonNode, key: String, values: Set<String>) {
    if (text(n, key) !in values) bad("Неизвестное значение $key")
  }

  private fun bool(n: JsonNode, key: String) {
    if (n.get(key)?.isBoolean != true) bad("Некорректное поле $key")
  }

  private fun number(
    n: JsonNode,
    key: String,
    required: Boolean = false,
    integer: Boolean = false,
    min: Double = 0.0,
    max: Double = 1e15,
  ) {
    val value = n.get(key)
    if (value == null || value.isNull) {
      if (required) bad("Отсутствует $key")
      return
    }
    if (
      !value.isNumber ||
        !value.asDouble().isFinite() ||
        value.asDouble() !in min..max ||
        integer && !value.isIntegralNumber
    )
      bad("Некорректное число $key")
  }

  private fun uuid(value: JsonNode?): UUID {
    if (value?.isString != true) bad("Ожидается UUID")
    val raw = value.asString()
    val id =
      try {
        UUID.fromString(raw)
      } catch (e: IllegalArgumentException) {
        bad("Некорректный UUID")
      }
    if (id.toString() != raw.lowercase()) bad("Некорректный UUID")
    return id
  }

  private fun array(n: JsonNode, key: String, max: Int = 1000): List<JsonNode> {
    val v = n.get(key) ?: bad("Отсутствует $key")
    if (!v.isArray || v.size() > max) bad("Некорректный список $key")
    return v.toList()
  }

  private fun ids(n: JsonNode, key: String) {
    val ids = array(n, key).map(::uuid)
    if (ids.distinct().size != ids.size) bad("Дубли в $key")
  }

  private fun equipment(n: JsonNode) {
    val entries = array(n, "equipmentIds", 200)
    if (
      entries.any { !it.isString || it.asString() !in coverage() } ||
        entries.distinct().size != entries.size
    )
      bad("Некорректное оборудование")
  }

  private val coachSetFields =
    setOf(
      "syncId",
      "originalWeightKg",
      "originalReps",
      "originalDurationSec",
      "originalSpeedKmh",
      "originalInclinePct",
      "targetWeightKg",
      "targetReps",
      "targetDurationSec",
      "targetSpeedKmh",
      "targetInclinePct",
      "actualWeightKg",
      "actualReps",
      "actualDurationSec",
      "actualSpeedKmh",
      "actualInclinePct",
      "setType",
      "reportedFeelingsJson",
      "restSnapshotJson",
      "coachMutationRevision",
    )

  private fun set(n: JsonNode, completed: Boolean) {
    shape(
      n,
      if (completed) setFields + setOf("setIndex", "isCompleted", "completedAt") + coachSetFields
      else setFields,
    )
    setFields.forEach {
      number(
        n,
        it,
        integer = it in setOf("reps", "durationSec"),
        min = if (it == "inclinePct") -100.0 else 0.0,
        max = 1e6,
      )
    }
    if (completed) {
      n["syncId"]?.let(::uuid)
      coachSetFields
        .filter { it.startsWith("original") || it.startsWith("target") || it.startsWith("actual") }
        .forEach {
          number(
            n,
            it,
            integer = it.endsWith("Reps") || it.endsWith("DurationSec"),
            min = if (it.endsWith("InclinePct")) -100.0 else 0.0,
            max = 1e6,
          )
        }
      n["setType"]?.let {
        enum(
          n,
          "setType",
          setOf(
            "WORK",
            "WARMUP",
            "TIMED",
            "CARDIO",
            "COUNTERWEIGHT",
            "BAND",
            "INTERRUPTED",
            "UNKNOWN",
          ),
        )
      }
      number(n, "coachMutationRevision", integer = true)
      n["reportedFeelingsJson"]?.let {
        val value = json.readTree(text(n, "reportedFeelingsJson", 4096))
        if (
          !value.isArray ||
            value.size() > 10 ||
            value.any { v ->
              !v.isString ||
                v.asString() !in setOf("PAIN", "FATIGUE", "TECHNIQUE_BREAKDOWN", "INTERRUPTED")
            }
        )
          bad("Некорректные ощущения")
      }
      n["restSnapshotJson"]
        ?.takeUnless { it.isNull }
        ?.let {
          val value = json.readTree(text(n, "restSnapshotJson", 4096))
          shape(
            value,
            setOf("plannedSeconds", "startedAtMillis", "completedAtMillis", "extraSeconds"),
          )
          listOf("plannedSeconds", "startedAtMillis", "completedAtMillis", "extraSeconds")
            .forEach { field -> number(value, field, integer = true) }
        }
      number(n, "setIndex", true, true, max = 1000.0)
      bool(n, "isCompleted")
      number(n, "completedAt", integer = true)
    }
  }

  fun validate(kind: String, n: JsonNode) {
    when (kind) {
      "exercise" -> {
        shape(
          n,
          setOf(
            "name",
            "muscleGroup",
            "type",
            "isCustom",
            "updatedAt",
            "needsMuscleMapReview",
            "equipmentRequirementState",
            "muscles",
            "equipmentIds",
          ),
        )
        text(n, "name")
        enum(
          n,
          "muscleGroup",
          setOf("CHEST", "BACK", "LEGS", "SHOULDERS", "ARMS", "CORE", "CARDIO", "FULL_BODY"),
        )
        enum(n, "type", setOf("STRENGTH", "TIMED", "CARDIO"))
        bool(n, "isCustom")
        bool(n, "needsMuscleMapReview")
        number(n, "updatedAt", true, true)
        enum(n, "equipmentRequirementState", setOf("UNKNOWN", "KNOWN"))
        equipment(n)
        val maps = array(n, "muscles", muscles.size)
        maps.forEach {
          shape(it, setOf("muscle", "contribution"))
          enum(it, "muscle", muscles)
          number(it, "contribution", true, true)
          if (it["contribution"].asInt() !in setOf(0, 50, 100)) bad("Некорректная роль мышцы")
        }
        if (maps.map { it["muscle"].asString() }.distinct().size != maps.size) bad("Повтор мышцы")
      }
      "gym" -> {
        shape(n, setOf("name", "updatedAt", "inventoryConfigured", "exerciseIds", "equipmentIds"))
        text(n, "name")
        number(n, "updatedAt", true, true)
        bool(n, "inventoryConfigured")
        ids(n, "exerciseIds")
        equipment(n)
      }
      "routine" -> {
        shape(n, setOf("name", "note", "updatedAt", "exercises", "gymIds"))
        text(n, "name")
        text(n, "note", 10000, true)
        number(n, "updatedAt", true, true)
        ids(n, "gymIds")
        val rows = array(n, "exercises", 200)
        rows.forEach {
          shape(it, setOf("exerciseId", "position", "restSeconds", "plannedSets"))
          uuid(it["exerciseId"])
          number(it, "position", true, true, max = 1000.0)
          number(it, "restSeconds", integer = true, max = 86400.0)
          array(it, "plannedSets", 1000).forEach { s -> set(s, false) }
        }
        if (rows.map { it["position"].asInt() }.distinct().size != rows.size)
          bad("Повтор позиции упражнения")
      }
      "workout" -> {
        shape(
          n,
          setOf(
            "name",
            "note",
            "routineId",
            "startedAt",
            "finishedAt",
            "exercises",
            "gymIds",
            "coachRevision",
          ),
        )
        text(n, "name")
        text(n, "note", 10000, true)
        number(n, "coachRevision", integer = true)
        number(n, "startedAt", true, true)
        number(n, "finishedAt", integer = true)
        ids(n, "gymIds")
        n["routineId"]?.takeUnless { it.isNull }?.let(::uuid)
        n["finishedAt"]
          ?.takeUnless { it.isNull }
          ?.let { if (it.asLong() < n["startedAt"].asLong()) bad("Окончание раньше начала") }
        val rows = array(n, "exercises", 200)
        rows.forEach { row ->
          shape(row, setOf("sectionId", "exerciseId", "position", "sets"))
          uuid(row["sectionId"])
          uuid(row["exerciseId"])
          number(row, "position", true, true, max = 1000.0)
          val sets = array(row, "sets", 1000)
          sets.forEach { set(it, true) }
          val stableIds = sets.mapNotNull { it.get("syncId")?.asString() }
          if (stableIds.distinct().size != stableIds.size) bad("Повтор идентификатора подхода")
          if (sets.map { it["setIndex"].asInt() }.distinct().size != sets.size)
            bad("Повтор подхода")
        }
        val allSetIds =
          rows.flatMap { it["sets"].toList() }.mapNotNull { it.get("syncId")?.asString() }
        if (allSetIds.distinct().size != allSetIds.size)
          bad("Повтор идентификатора подхода в тренировке")
        if (
          rows.map { it["sectionId"].asString() }.distinct().size != rows.size ||
            rows.map { it["position"].asInt() }.distinct().size != rows.size
        )
          bad("Повтор секции тренировки")
      }
      "measurement" -> {
        shape(n, measurementFields)
        measurementFields.forEach {
          number(
            n,
            it,
            required = it == "measuredAt",
            integer =
              it in
                setOf(
                  "measuredAt",
                  "visceralFatLevel",
                  "inBodyScore",
                  "basalMetabolicRateKcal",
                  "recommendedCalorieIntakeKcal",
                ),
          )
        }
        number(n, "bodyFatPercentage", max = 100.0)
      }
      "schedule" -> {
        shape(n, setOf("routineId", "dateTimeMillis", "calendarEventId"))
        uuid(n["routineId"])
        number(n, "dateTimeMillis", true, true)
        text(n, "calendarEventId", 1024, true)
      }
      else -> bad("Неизвестный тип объекта")
    }
  }

  fun references(
    records: Map<RecordKey, Record>,
    changed: Set<RecordKey> = records.keys,
    before: Map<RecordKey, Record> = emptyMap(),
  ) {
    val coverage = coverage()
    val configuration =
      changed
        .filter { key ->
          val old = before[key]?.payload
          val next = records[key]?.payload
          when (key.kind) {
            "exercise" ->
              old?.get("equipmentIds") != next?.get("equipmentIds") ||
                old?.get("equipmentRequirementState") != next?.get("equipmentRequirementState")
            "gym" ->
              listOf("equipmentIds", "inventoryConfigured", "exerciseIds").any {
                old?.get(it) != next?.get(it)
              }
            else -> false
          }
        }
        .toSet()

    val sectionIds = mutableSetOf<UUID>()
    fun ref(kind: String, id: JsonNode) {
      if (records[RecordKey(kind, uuid(id))]?.deleted != false) bad("Ссылка на отсутствующий $kind")
    }
    records.values
      .filter { !it.deleted }
      .forEach { r ->
        val n = r.payload!!
        when (r.kind) {
          "gym" -> n["exerciseIds"].forEach { ref("exercise", it) }
          "routine",
          "workout" -> {
            n["exercises"].forEach { ref("exercise", it["exerciseId"]) }
            n["gymIds"].forEach { ref("gym", it) }
            // Completed workouts are historical snapshots; changing today's inventory must not
            // invalidate their past sets. Programs and active workouts enforce current coverage.
            val affected =
              RecordKey(r.kind, r.id) in changed || referencesOf(r).any { it in configuration }
            if (
              affected && (r.kind == "routine" || n["finishedAt"] == null || n["finishedAt"].isNull)
            ) {
              n["exercises"].forEach { row ->
                val exercise =
                  records.getValue(RecordKey("exercise", uuid(row["exerciseId"]))).payload!!
                n["gymIds"].forEach { gymId ->
                  val gym = records.getValue(RecordKey("gym", uuid(gymId))).payload!!
                  val available =
                    if (!gym["inventoryConfigured"].asBoolean()) {
                      gym["exerciseIds"].toList().any { uuid(it) == uuid(row["exerciseId"]) }
                    } else {
                      val coverage =
                        gym["equipmentIds"]
                          .toList()
                          .flatMap { coverage[it.asString()].orEmpty() }
                          .toSet()
                      exercise["equipmentRequirementState"].asString() == "KNOWN" &&
                        exercise["equipmentIds"].toList().all { it.asString() in coverage }
                    }
                  if (!available)
                    bad(
                      "Оборудование зала не покрывает упражнение программы или активной тренировки"
                    )
                }
              }
            }
            if (r.kind == "workout")
              n["routineId"]?.takeUnless { it.isNull }?.let { ref("routine", it) }
            if (r.kind == "workout")
              n["exercises"].forEach {
                if (!sectionIds.add(uuid(it["sectionId"])))
                  bad("Секция уже принадлежит другой тренировке")
              }
          }
          "schedule" -> ref("routine", n["routineId"])
        }
      }
  }

  fun referencesOf(r: Record): Set<RecordKey> {
    val n = r.payload ?: return emptySet()
    val refs = mutableSetOf<RecordKey>()
    fun add(kind: String, node: JsonNode?) {
      node?.takeUnless { it.isNull }?.let { refs.add(RecordKey(kind, uuid(it))) }
    }
    when (r.kind) {
      "gym" -> n["exerciseIds"].forEach { add("exercise", it) }
      "routine",
      "workout" -> {
        n["exercises"].forEach { add("exercise", it["exerciseId"]) }
        n["gymIds"].forEach { add("gym", it) }
        if (r.kind == "workout") add("routine", n["routineId"])
      }
      "schedule" -> add("routine", n["routineId"])
    }
    return refs
  }

  fun archivedReferences(
    records: Map<RecordKey, Record>,
    before: Map<RecordKey, Record>,
    archived: Set<RecordKey>,
  ) {
    val archivedEquipment = equipmentRows.findAll().filter { it.archived }.map { it.id }.toSet()
    records.forEach { (key, r) ->
      val old = before[key]
      if ((referencesOf(r) - (old?.let(::referencesOf) ?: emptySet())).any { it in archived })
        bad("Архивный объект недоступен для нового выбора")
      val added =
        (r.payload?.get("equipmentIds")?.toList()?.map { it.asString() } ?: emptyList()).toSet() -
          (old?.payload?.get("equipmentIds")?.toList()?.map { it.asString() } ?: emptyList())
            .toSet()
      if (added.any { it in archivedEquipment })
        bad("Архивное оборудование недоступно для нового выбора")
    }
  }
}
