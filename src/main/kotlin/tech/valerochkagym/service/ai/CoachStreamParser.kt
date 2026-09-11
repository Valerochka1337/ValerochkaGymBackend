package tech.valerochkagym.service.ai

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Parses bounded SSE records independently of HTTP and UTF-8 packet boundaries. */
internal class CoachStreamParser(
  private val json: ObjectMapper,
  private val delta: (String) -> Unit,
) {
  private val line = ByteArrayOutputStream()
  private val data = StringBuilder()
  private val text = StringBuilder()
  private val calls = sortedMapOf<Int, MutableMap<String, String>>()
  private var total = 0
  private var eventBytes = 0
  private var cr = false
  private var reason: String? = null
  var done = false
    private set

  fun accept(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
      val b = buffer.get().toInt() and 255
      if (++total > 2 * 1024 * 1024 || ++eventBytes > 240 * 1024) invalid()
      if (b == 10 && cr) {
        cr = false
        continue
      }
      cr = b == 13
      if (b == 10 || b == 13) {
        val value =
          Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(line.toByteArray()))
            .toString()
        line.reset()
        if (value.isEmpty()) {
          event()
          eventBytes = 0
        } else if (value.startsWith("data:")) {
          data.append(value.substring(5).removePrefix(" ")).append('\n')
        }
      } else line.write(b)
    }
  }

  private fun event() {
    if (data.isEmpty()) return
    val value = data.toString().removeSuffix("\n")
    data.setLength(0)
    if (done) invalid()
    if (value == "[DONE]") {
      if (reason == null) invalid()
      done = true
      return
    }
    val root =
      json
        .tokenStreamFactory()
        .rebuild()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()
        .createParser(value)
        .use { parser -> json.readTree(parser).also { if (parser.nextToken() != null) invalid() } }
    if (root["error"]?.let { !it.isNull } == true) invalid()
    val choices = root["choices"]
    if (choices?.isArray != true) invalid()
    if (choices.isEmpty) return // usage-only chunk
    if (choices.size() != 1 || reason != null) invalid()
    val choice = choices[0]
    if (choice["index"]?.isIntegralNumber != true || choice["index"].asInt() != 0) invalid()
    val part = choice["delta"]
    if (part?.isObject != true || part["refusal"]?.let { !it.isNull } == true) invalid()
    part["role"]?.let { if (it.asString() != "assistant") invalid() }
    part["content"]
      ?.takeUnless { it.isNull }
      ?.let {
        if (!it.isString || text.length + it.asString().length > 16000) invalid()
        text.append(it.asString())
        if (it.asString().isNotEmpty()) delta(it.asString())
      }
    part["tool_calls"]
      ?.takeUnless { it.isNull }
      ?.let { fragments ->
        if (!fragments.isArray) invalid()
        fragments.forEach { fragment ->
          val indexNode = fragment["index"] ?: invalid()
          if (!indexNode.isIntegralNumber || indexNode.asInt() !in 0..15) invalid()
          val call = calls.getOrPut(indexNode.asInt()) { mutableMapOf() }
          fun append(key: String, node: JsonNode?, max: Int) {
            if (node == null) return
            if (!node.isString) invalid()
            val joined = call.getOrDefault(key, "") + node.asString()
            if (joined.length > max) invalid()
            call[key] = joined
          }
          append("id", fragment["id"], 200)
          fragment["type"]?.let {
            if (it.asString() != "function") invalid()
            call["type"] = "function"
          }
          fragment["function"]?.let {
            if (!it.isObject) invalid()
            append("name", it["name"], 100)
            append("arguments", it["arguments"], 32000)
          }
        }
      }
    choice["finish_reason"]
      ?.takeUnless { it.isNull }
      ?.let {
        if (it.asString() !in setOf("stop", "tool_calls")) invalid()
        reason = it.asString()
      }
  }

  fun completion(): JsonNode {
    if (!done) invalid()
    val message = mutableMapOf<String, Any>("role" to "assistant", "content" to text.toString())
    if (calls.isNotEmpty()) {
      if (calls.keys.toList() != (0 until calls.size).toList()) invalid()
      message["tool_calls"] =
        calls.values.map {
          mapOf(
            "id" to it["id"],
            "type" to it["type"],
            "function" to mapOf("name" to it["name"], "arguments" to it["arguments"]),
          )
        }
    }
    return CoachCompletionSanitizer.sanitize(
      json.valueToTree(
        mapOf("choices" to listOf(mapOf("finish_reason" to reason, "message" to message)))
      ),
      json,
    )
  }

  private fun invalid(): Nothing = throw aiError("ai_invalid_response")
}
