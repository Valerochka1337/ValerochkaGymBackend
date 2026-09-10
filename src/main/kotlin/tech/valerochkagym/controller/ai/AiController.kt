package tech.valerochkagym.controller.ai

import java.util.concurrent.FutureTask
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.service.ai.*
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1/ai")
class AiController(private val service: AiActionService) {
  @GetMapping("/status") fun status() = service.status()

  @PostMapping("/exercise-drafts")
  fun exercise(
    @AuthenticationPrincipal identity: Identity,
    @RequestBody request: ExerciseDraftRequest,
  ): DeferredResult<AiDraftResponse> = async { service.exercise(identity, request) }

  @PostMapping("/inbody-drafts")
  fun inbody(
    @AuthenticationPrincipal identity: Identity,
    @RequestBody request: InBodyDraftRequest,
    @RequestHeader("X-Health-AI-Disclosure-Revision") disclosureRevision: Long,
  ): DeferredResult<AiDraftResponse> = async {
    service.inbody(identity, request, disclosureRevision)
  }

  private fun async(action: () -> AiDraftResponse): DeferredResult<AiDraftResponse> {
    val result = DeferredResult<AiDraftResponse>(45000)
    val task = FutureTask {
      try {
        result.setResult(action())
      } catch (e: Exception) {
        result.setErrorResult(
          if (e is tech.valerochkagym.controller.advice.ApiException) e
          else aiError("ai_unavailable")
        )
      }
      Unit
    }
    result.onTimeout {
      task.cancel(true)
      result.setErrorResult(aiError("ai_timeout"))
    }
    result.onError { task.cancel(true) }
    result.onCompletion { if (!task.isDone) task.cancel(true) }
    Thread.ofVirtual().start(task)
    return result
  }
}
