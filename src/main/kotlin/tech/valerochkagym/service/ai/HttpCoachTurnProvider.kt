package tech.valerochkagym.service.ai

import java.net.http.*
import java.time.Duration
import java.util.concurrent.*
import tech.valerochkagym.config.CoachProviderSettings
import tech.valerochkagym.controller.advice.ApiException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class HttpCoachTurnProvider(
  private val settings: CoachProviderSettings,
  private val json: ObjectMapper,
  private val client: HttpClient =
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build(),
  private val deadlineMillis: Long = 45000,
) : CoachTurnProvider {
  override fun catalog() = CoachModelCatalog("AVAILABLE", settings.defaultModel, settings.models)

  override fun complete(input: CoachTurnInput): JsonNode {
    if (input.model !in settings.models) throw aiError("ai_unavailable")
    var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
    try {
      // Tool turns deliberately omit response_format. No provider reasoning/usage is forwarded.
      val body =
        mapOf(
          "model" to input.model,
          "store" to false,
          "stream" to false,
          "n" to 1,
          "max_completion_tokens" to 4096,
          "messages" to input.messages,
          "tools" to input.tools,
          "tool_choice" to "auto",
        )
      val request =
        HttpRequest.newBuilder(settings.provider.endpoint)
          .timeout(Duration.ofMillis(deadlineMillis))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer ${settings.provider.key}")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
          .build()
      pending = client.sendAsync(request) { BoundedAiBodySubscriber(240 * 1024) }
      val response = pending.get(deadlineMillis, TimeUnit.MILLISECONDS)
      if (response.statusCode() != 200) throw aiError("ai_unavailable")
      return CoachCompletionSanitizer.sanitize(json.readTree(response.body()), json)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw aiError("ai_timeout")
    } catch (_: TimeoutException) {
      throw aiError("ai_timeout")
    } catch (e: ApiException) {
      throw e
    } catch (e: Exception) {
      val cause = e.cause ?: e
      if (cause is HttpTimeoutException) throw aiError("ai_timeout")
      if (cause is ApiException) throw cause
      throw aiError(
        if (e is ExecutionException || e is java.io.IOException) "ai_unavailable"
        else "ai_invalid_response"
      )
    } finally {
      pending?.takeUnless { it.isDone }?.cancel(true)
    }
  }
}

internal object CoachCompletionSanitizer {
  fun sanitize(root: JsonNode, json: ObjectMapper): JsonNode {
    try {
      if (root["error"]?.let { !it.isNull } == true) throw aiError("ai_invalid_response")
      val choices = root["choices"]
      if (choices?.isArray != true || choices.size() != 1) throw aiError("ai_invalid_response")
      val choice = choices[0]
      val message = choice["message"]
      val reason = choice["finish_reason"]?.asString()
      if (
        reason !in setOf("stop", "tool_calls") ||
          message?.get("role")?.asString() != "assistant" ||
          message["refusal"]?.let { !it.isNull } == true
      )
        throw aiError("ai_invalid_response")
      val content = message["content"]?.takeUnless { it.isNull }
      if (content != null) CoachTurnService.string(content, 16000, allowEmpty = true)
      val calls = message["tool_calls"]?.takeUnless { it.isNull }
      if ((reason == "tool_calls") != (calls != null)) throw aiError("ai_invalid_response")
      if (calls != null) {
        if (!calls.isArray || calls.size() !in 1..16) throw aiError("ai_invalid_response")
        calls.forEach(CoachTurnService::validateCall)
        if (
          (0 until calls.size()).map { calls[it]["id"].asString() }.distinct().size != calls.size()
        )
          throw aiError("ai_invalid_response")
      } else if (content == null || content.asString().isBlank())
        throw aiError("ai_invalid_response")
      val safeMessage = mutableMapOf<String, Any>("role" to "assistant")
      content?.let { safeMessage["content"] = it }
      calls?.let { safeMessage["tool_calls"] = it }
      return json.valueToTree(
        mapOf("choices" to listOf(mapOf("finish_reason" to reason, "message" to safeMessage)))
      )
    } catch (_: Exception) {
      throw aiError("ai_invalid_response")
    }
  }
}
