package tech.valerochkagym.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CoachStreamOpenApi {
  @Bean
  fun coachStreamingContract() = OpenApiCustomizer { doc ->
    val operation = doc.paths["/v1/ai/coach-turn/stream"]?.post ?: return@OpenApiCustomizer
    operation.summary = "Stream a stateless Live Coach turn"
    operation.description =
      """
      Same JSON request as POST /v1/ai/coach-turn. text_delta carries {requestId, model, delta};
      completed carries {requestId, model, completion}, exactly the existing validated response;
      error carries {requestId, code, message}. Text is provisional and tools appear only in completed.
      Exactly one terminal event (completed or error) is sent when the connection is available, then EOF.
      EOF without completed is an incomplete response. No retries, resume or Last-Event-ID.
      Heartbeat comments every 10 seconds; session validity checked before every send.
      Revocation emits error/unauthorized and cancels generation. Shared with coach-turn: 2 concurrent
      exchanges and 30 requests/minute/user, 512 KiB request, 45 second total deadline.
      Upstream SSE: 2 MiB total, 240 KiB per event; existing completion semantic limits apply.
      Post-open error codes: ai_invalid_response, ai_unavailable, ai_timeout, unauthorized.
      Cache-Control: no-store; X-Accel-Buffering: no.
      """
        .trimIndent()
    doc.components = doc.components ?: Components()
    doc.components.addSecuritySchemes(
      "coachBearer",
      SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"),
    )
    operation.security = listOf(SecurityRequirement().addList("coachBearer"))
    operation.parameters = operation.parameters?.filterNot { it.name == "Authorization" }
    val request =
      Schema<Any>()
        .type("object")
        .addProperty("requestId", StringSchema().format("uuid"))
        .addProperty(
          "model",
          StringSchema().description("Optional allowlisted model; defaults to catalog default"),
        )
        .addProperty(
          "messages",
          io.swagger.v3.oas.models.media
            .ArraySchema()
            .items(Schema<Any>().type("object"))
            .minItems(1)
            .maxItems(80),
        )
        .addProperty(
          "tools",
          io.swagger.v3.oas.models.media
            .ArraySchema()
            .items(Schema<Any>().type("object"))
            .minItems(4)
            .maxItems(4),
        )
        .required(listOf("requestId", "messages", "tools"))
        .additionalProperties(false)
    operation.requestBody.content =
      Content().addMediaType("application/json", MediaType().schema(request))
    val example =
      """
      event:text_delta
      data:{"requestId":"123e4567-e89b-12d3-a456-426614174000","model":"coach","delta":"Hello"}

      event:completed
      data:{"requestId":"123e4567-e89b-12d3-a456-426614174000","model":"coach","completion":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Hello"}}]}}


      """
        .trimIndent()
    operation.responses.addApiResponse(
      "200",
      ApiResponse()
        .description("SSE; terminal completed or error, followed by EOF")
        .content(
          Content()
            .addMediaType(
              "text/event-stream",
              MediaType()
                .schema(StringSchema())
                .addExamples("completed", Example().value(example))
                .addExamples(
                  "error",
                  Example()
                    .value(
                      "event:error\ndata:{\"requestId\":\"123e4567-e89b-12d3-a456-426614174000\",\"code\":\"unauthorized\",\"message\":\"Требуется авторизация\"}\n\n"
                    ),
                ),
            )
        ),
    )
    for ((status, description) in
      mapOf(
        "400" to "Invalid request",
        "401" to "Unauthorized",
        "413" to "Request exceeds 512 KiB",
        "429" to "rate_limited",
        "503" to "ai_busy or ai_unavailable",
      )) {
      operation.responses.addApiResponse(
        status,
        ApiResponse()
          .description("Before SSE opens: $description")
          .content(
            Content()
              .addMediaType(
                "application/json",
                MediaType()
                  .schema(
                    Schema<Any>()
                      .type("object")
                      .addProperty("code", StringSchema())
                      .addProperty("message", StringSchema())
                      .required(listOf("code", "message"))
                  ),
              )
          ),
      )
    }
  }
}
