package tech.valerochkagym.config

import java.net.URI
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tech.valerochkagym.service.ai.*
import tools.jackson.databind.ObjectMapper

// Intentionally not a data class: a diagnostic toString must never expose credentials.
class AiProviderSettings(
  val endpoint: URI,
  val key: String,
  val textModel: String,
  val visionModel: String,
) {
  companion object {
    fun from(values: (String) -> String?): AiProviderSettings? {
      if (values("AI_ENABLED") != "true" || values("AI_PROVIDER") != "openai") return null
      val raw = values("AI_BASE_URL") ?: return null
      val uri =
        try {
          URI(raw)
        } catch (_: Exception) {
          return null
        }
      if (
        uri.scheme != "https" ||
          uri.host.isNullOrBlank() ||
          uri.userInfo != null ||
          uri.query != null ||
          uri.fragment != null ||
          uri.path !in setOf("", "/", "/v1", "/v1/")
      )
        return null
      val secrets =
        listOf("AI_API_KEY", "AI_TEXT_MODEL", "AI_VISION_MODEL").map { values(it).orEmpty() }
      if (secrets.any { it.isBlank() || it.any { c -> c.code < 32 || c.code == 127 } }) return null
      return AiProviderSettings(
        URI("https", null, uri.host, uri.port, "/v1/chat/completions", null, null),
        secrets[0],
        secrets[1],
        secrets[2],
      )
    }
  }
}

@Configuration
class AiConfiguration {
  @Bean
  fun aiProvider(env: Environment, json: ObjectMapper): AiProvider {
    val settings = AiProviderSettings.from(env::getProperty)
    return if (settings == null) UnconfiguredAiProvider()
    else HttpOpenAiChatCompletionsProvider(settings, json)
  }
}
