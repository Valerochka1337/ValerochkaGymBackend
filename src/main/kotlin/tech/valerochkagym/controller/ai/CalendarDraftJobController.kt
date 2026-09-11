package tech.valerochkagym.controller.ai

import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.service.ai.CalendarDraftJobService
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1/ai/calendar-draft-jobs")
class CalendarDraftJobController(private val jobs: CalendarDraftJobService) {
  @PostMapping(consumes = ["application/json"])
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun submit(@AuthenticationPrincipal identity: Identity, @RequestBody raw: ByteArray) =
    jobs.submit(identity, raw)

  @DeleteMapping("/{requestId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun cancel(@AuthenticationPrincipal identity: Identity, @PathVariable requestId: UUID) =
    jobs.cancel(identity, requestId)

  @GetMapping("/{requestId}")
  fun status(@AuthenticationPrincipal identity: Identity, @PathVariable requestId: UUID) =
    jobs.status(identity, requestId)
}
