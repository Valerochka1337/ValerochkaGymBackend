package tech.valerochkagym.controller.data

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.controller.model.CoachPush
import tech.valerochkagym.service.data.CoachJournalService
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1/coach/journal")
class CoachController(private val coach: CoachJournalService) {
  @PostMapping
  fun push(
    @AuthenticationPrincipal identity: Identity,
    @RequestBody request: CoachPush,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
  ) = coach.push(identity.userId, request, version)

  @GetMapping
  fun page(
    @AuthenticationPrincipal identity: Identity,
    @RequestParam(defaultValue = "0") after: Long,
    @RequestParam(required = false) cursor: String?,
    @RequestParam(defaultValue = "100") limit: Int,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
  ) = coach.page(identity.userId, after, cursor, limit, version)
}
