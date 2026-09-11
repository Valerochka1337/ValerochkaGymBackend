package tech.valerochkagym.controller.ai

import jakarta.servlet.http.HttpServletResponse
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.service.ai.*

/** One sender and one queued delta apply backpressure all the way to the provider. */
internal class CoachStreamExchange(
  private val turn: CoachTurnService.StreamTurn,
  private val response: HttpServletResponse,
  private val authorized: () -> Boolean,
) {
  private data class Event(val name: String, val data: Any)

  private val queue = ArrayBlockingQueue<Event>(1)
  private val stopped = AtomicBoolean()
  private val emitter = SseEmitter(46000L)
  private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
  private val producer = FutureTask {
    try {
      val result =
        turn.run { delta ->
          queue.put(
            Event(
              "text_delta",
              mapOf(
                "requestId" to turn.input.requestId,
                "model" to turn.input.model,
                "delta" to delta,
              ),
            )
          )
        }
      queue.put(Event("completed", result))
    } catch (e: Exception) {
      if (e !is InterruptedException && !Thread.currentThread().isInterrupted && !stopped.get())
        queue.put(error(e))
    }
  }
  private val sender = FutureTask { sendLoop() }
  private val watchdog = FutureTask {
    try {
      TimeUnit.NANOSECONDS.sleep((deadline - System.nanoTime()).coerceAtLeast(1))
      producer.cancel(true)
      turn.close()
      sender.cancel(true)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  fun start(): SseEmitter {
    emitter.onCompletion { stop() }
    emitter.onError { stop() }
    emitter.onTimeout {
      stop()
      emitter.complete()
    }
    Thread.ofVirtual().start(watchdog)
    Thread.ofVirtual().start(sender)
    return emitter
  }

  private fun checkAuth() {
    if (!authorized()) unauthorized()
  }

  private fun sendLoop() {
    var terminalAttempted = false
    try {
      checkAuth()
      emitter.send(SseEmitter.event().comment("heartbeat"))
      // SseEmitter buffers sends until MVC initializes it. Allow only this first heartbeat
      // into that buffer, then wait for its flush before starting the producer.
      while (!response.isCommitted && !stopped.get()) Thread.sleep(1)
      if (stopped.get()) return
      Thread.ofVirtual().start(producer)
      var heartbeatAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      while (!stopped.get()) {
        val left = deadline - System.nanoTime()
        if (left <= 0) throw aiError("ai_timeout")
        if (System.nanoTime() >= heartbeatAt) {
          checkAuth()
          emitter.send(SseEmitter.event().comment("heartbeat"))
          heartbeatAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        }
        val event =
          queue.poll(
            minOf(left, (heartbeatAt - System.nanoTime()).coerceAtLeast(1)),
            TimeUnit.NANOSECONDS,
          )
        if (System.nanoTime() >= deadline) throw aiError("ai_timeout")
        checkAuth()
        if (event != null) {
          terminalAttempted = event.name != "text_delta"
          emitter.send(SseEmitter.event().name(event.name).data(event.data))
          if (event.name != "text_delta") return
        }
      }
    } catch (e: Exception) {
      if (!stopped.get() && !terminalAttempted) {
        Thread.interrupted()
        val failure =
          try {
            checkAuth()
            error(e)
          } catch (auth: Exception) {
            error(auth)
          }
        runCatching { emitter.send(SseEmitter.event().name("error").data(failure.data)) }
      }
    } finally {
      stop()
      emitter.complete()
    }
  }

  private fun error(e: Exception): Event {
    val safe =
      when (e) {
        is ApiException -> e
        is InterruptedException -> aiError("ai_timeout")
        else -> aiError("ai_unavailable")
      }
    return Event(
      "error",
      mapOf("requestId" to turn.input.requestId, "code" to safe.code, "message" to safe.message),
    )
  }

  private fun stop() {
    if (stopped.compareAndSet(false, true)) {
      producer.cancel(true)
      sender.cancel(true)
      watchdog.cancel(true)
      turn.close()
    }
  }
}
