package tech.valerochkagym.service.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import tech.valerochkagym.config.AiProviderSettings
import tech.valerochkagym.config.CoachProviderSettings
import tech.valerochkagym.controller.advice.ApiException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class HttpCoachTurnProviderTest {
  private val json = JsonMapper.builder().build()
  private lateinit var server: HttpServer
  private lateinit var executor: ExecutorService
  private val captured = AtomicReference<JsonNode>()
  private var handler: (com.sun.net.httpserver.HttpExchange) -> Unit = {}

  @BeforeEach
  fun setup() {
    executor = Executors.newCachedThreadPool()
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = executor
    server.createContext("/v1/chat/completions") { e ->
      try {
        captured.set(json.readTree(e.requestBody))
        handler(e)
      } finally {
        e.close()
      }
    }
    server.start()
  }

  @AfterEach
  fun close() {
    server.stop(0)
    executor.shutdownNow()
  }

  private fun provider(timeout: Long = 2000) =
    HttpCoachTurnProvider(
      CoachProviderSettings(
        AiProviderSettings(
          URI("http://127.0.0.1:${server.address.port}/v1/chat/completions"),
          "dummy-key",
          "text",
          "vision",
        ),
        "coach",
        listOf("coach"),
      ),
      json,
      deadlineMillis = timeout,
    )

  private fun input() =
    CoachTurnInput(
      "fixture",
      "coach",
      json.readTree("[{\"role\":\"user\",\"content\":\"test\"}]"),
      json.readTree("[]"),
    )

  private fun response(tool: Boolean = true) =
    if (tool)
      """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","reasoning":"PRIVATE_TRACE","tool_calls":[{"id":"call-1","type":"function","function":{"name":"get_workout_state","arguments":"{}"}}]}}],"usage":{"secret":"PRIVATE_TRACE"}}"""
    else
      """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Готово"}}]}"""

  private fun respond(e: com.sun.net.httpserver.HttpExchange, body: String, status: Int = 200) {
    val bytes = body.toByteArray()
    e.sendResponseHeaders(status, bytes.size.toLong())
    e.responseBody.write(bytes)
  }

  private fun chunk(delta: String, reason: String = "null") =
    "data: {\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":$reason}]}\r\n\r\n"

  @Test
  fun `stream delivers UTF8 text before upstream finishes and hides metadata`() {
    val release = CountDownLatch(1)
    val first = CountDownLatch(1)
    handler = { e ->
      e.sendResponseHeaders(200, 0)
      val initial = chunk("""{"role":"assistant","content":"Привет 🏋️","reasoning":"PRIVATE"}""")
      initial.toByteArray().forEach {
        e.responseBody.write(byteArrayOf(it))
        e.responseBody.flush()
      }
      release.await(3, TimeUnit.SECONDS)
      e.responseBody.write((chunk("{}", "\"stop\"") + "data: [DONE]\n\n").toByteArray())
    }
    val deltas = StringBuffer()
    val result =
      executor.submit<JsonNode> {
        provider(5000).stream(input()) {
          deltas.append(it)
          first.countDown()
        }
      }
    try {
      assertTrue(first.await(2, TimeUnit.SECONDS))
      assertFalse(result.isDone)
      assertTrue(captured.get()["stream"].asBoolean())
      assertEquals("Привет 🏋️", deltas.toString())
    } finally {
      release.countDown()
    }
    assertFalse(result.get(2, TimeUnit.SECONDS).toString().contains("PRIVATE"))
  }

  @Test
  fun `stream reconstructs indexed tools and fragmented arguments`() {
    val start =
      chunk(
        """{"tool_calls":[{"index":1,"id":"b","type":"function","function":{"name":"find_exercises","arguments":"{"}},{"index":0,"id":"a","type":"function","function":{"name":"get_workout_state","arguments":"{"}}]}"""
      )
    val finish =
      chunk(
        """{"tool_calls":[{"index":0,"function":{"arguments":"}"}},{"index":1,"function":{"arguments":"}"}}]}""",
        "\"tool_calls\"",
      )
    handler = { respond(it, start + finish + "data: [DONE]\n\n") }
    val result = provider().stream(input()) { fail("tools must never be deltas") }
    val calls = result["choices"][0]["message"]["tool_calls"]
    assertEquals(2, calls.size())
    assertEquals("a", calls[0]["id"].asString())
    assertEquals("{}", calls[1]["function"]["arguments"].asString())
    handler = {
      respond(it, (start + finish + "data: [DONE]\n\n").replace("find_exercises", "run_sql"))
    }
    assertEquals(
      "ai_invalid_response",
      assertThrows(ApiException::class.java) { provider().stream(input()) {} }.code,
    )
  }

  @Test
  fun `stream rejects malformed truncated refused and oversized events`() {
    val initial = chunk("""{"content":"partial"}""")
    for (body in
      listOf(
        initial,
        initial + chunk("{}", "\"stop\""),
        initial + "data: [DONE]\n\n",
        "data: invalid JSON\n\n",
        initial + chunk("{}", "\"length\"") + "data: [DONE]\n\n",
        chunk("""{"refusal":"No"}"""),
        "data: " + "x".repeat(240 * 1024),
        ":" + "x".repeat(200 * 1024) + "\n\n" + (": " + "x".repeat(200 * 1024) + "\n\n").repeat(10),
      )) {
      handler = { respond(it, body) }
      assertEquals(
        "ai_invalid_response",
        assertThrows(ApiException::class.java) { provider().stream(input()) {} }.code,
      )
    }
  }

  @Test
  fun `stream deadline includes stalled body after headers`() {
    val release = CountDownLatch(1)
    handler = { e ->
      e.sendResponseHeaders(200, 0)
      e.responseBody.write(chunk("""{"content":"partial"}""").toByteArray())
      e.responseBody.flush()
      release.await(3, TimeUnit.SECONDS)
    }
    try {
      assertEquals(
        "ai_timeout",
        assertThrows(ApiException::class.java) { provider(200).stream(input()) {} }.code,
      )
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `wire uses coach model tools and no forced JSON format or private trace`() {
    handler = {
      assertEquals("Bearer dummy-key", it.requestHeaders.getFirst("Authorization"))
      respond(it, response())
    }
    val result = provider().complete(input())
    assertEquals("coach", captured.get()["model"].asString())
    assertFalse(captured.get().has("response_format"))
    assertFalse(captured.get()["store"].asBoolean())
    assertFalse(captured.get()["stream"].asBoolean())
    assertTrue(captured.get().has("tools"))
    assertEquals(
      "get_workout_state",
      result["choices"][0]["message"]["tool_calls"][0]["function"]["name"].asString(),
    )
    assertFalse(result.toString().contains("PRIVATE_TRACE"))
    handler = { respond(it, response(false)) }
    assertEquals(
      "Готово",
      provider().complete(input())["choices"][0]["message"]["content"].asString(),
    )
  }

  @Test
  fun `invalid upstream results and errors never leak provider details`() {
    for (body in
      listOf(
        "{}",
        response().replace("get_workout_state", "run_sql"),
        response().replace("tool_calls\",\"message", "stop\",\"message"),
        response(false).replace("stop", "tool_calls"),
        response().replace("tool_calls\",\"message", "length\",\"message"),
        "x".repeat(240 * 1024 + 1),
      )) {
      handler = { respond(it, body) }
      assertEquals(
        "ai_invalid_response",
        assertThrows(ApiException::class.java) { provider().complete(input()) }.code,
      )
    }
    handler = { respond(it, "PRIVATE_KEY", 503) }
    val error = assertThrows(ApiException::class.java) { provider().complete(input()) }
    assertEquals("ai_unavailable", error.code)
    assertFalse(error.message.contains("PRIVATE_KEY"))
  }

  @Test
  fun `deadline cancels waiting provider request`() {
    val release = CountDownLatch(1)
    handler = {
      release.await(2, TimeUnit.SECONDS)
      respond(it, response(false))
    }
    try {
      assertEquals(
        "ai_timeout",
        assertThrows(ApiException::class.java) { provider(100).complete(input()) }.code,
      )
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `thread interruption cancels exchange and permits later request`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    handler = {
      entered.countDown()
      release.await(2, TimeUnit.SECONDS)
      respond(it, response(false))
    }
    val p = provider()
    val task =
      executor.submit {
        try {
          p.complete(input())
        } finally {
          finished.countDown()
        }
      }
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    task.cancel(true)
    release.countDown()
    assertTrue(finished.await(2, TimeUnit.SECONDS))
    handler = { respond(it, response(false)) }
    assertTrue(p.complete(input()).has("choices"))
  }
}
