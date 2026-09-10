package tech.valerochkagym.controller.data

import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.service.coachrelation.CoachRelationsService
import tech.valerochkagym.service.health.HealthRawBodyReader
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1/coach-relations")
class CoachRelationsController(
  private val service: CoachRelationsService,
  private val reader: HealthRawBodyReader,
  private val proposals: tech.valerochkagym.service.trainingproposal.TrainingProposalService,
) {
  @PostMapping("/{relationId}/training-proposals")
  @ResponseStatus(HttpStatus.CREATED)
  fun proposalCreate(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable relationId: UUID,
    request: HttpServletRequest,
  ) =
    proposals.mutateCoach(
      identity,
      relationId,
      null,
      reader.raw(request, 524288),
      "CREATE_COACH_PROPOSAL",
    )

  @PutMapping("/{relationId}/training-proposals/{proposalId}")
  fun proposalRevise(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable relationId: UUID,
    @PathVariable proposalId: UUID,
    request: HttpServletRequest,
  ) =
    proposals.mutateCoach(
      identity,
      relationId,
      proposalId,
      reader.raw(request, 524288),
      "REVISE_COACH_PROPOSAL",
    )

  @PostMapping("/{relationId}/training-proposals/{proposalId}/revoke")
  fun proposalRevoke(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable relationId: UUID,
    @PathVariable proposalId: UUID,
    request: HttpServletRequest,
  ) =
    proposals.mutateCoach(
      identity,
      relationId,
      proposalId,
      reader.raw(request, 524288),
      "REVOKE_COACH_PROPOSAL",
    )

  @PostMapping("/invitations")
  @ResponseStatus(HttpStatus.CREATED)
  fun create(@AuthenticationPrincipal identity: Identity, request: HttpServletRequest) =
    service.create(identity, reader.raw(request, 524288))

  @PostMapping("/invitations/accept")
  fun accept(@AuthenticationPrincipal identity: Identity, request: HttpServletRequest) =
    service.accept(identity, reader.raw(request, 524288))

  @PostMapping("/{relationId}/revoke")
  fun revoke(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable relationId: UUID,
    request: HttpServletRequest,
  ) = service.revoke(identity, relationId, reader.raw(request, 524288))

  @GetMapping("/{mode:clients|coaches}")
  fun directory(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable mode: String,
    @RequestParam(defaultValue = "20") limit: Int,
    @RequestParam(required = false) cursor: String?,
  ) = service.directory(identity, mode, limit, cursor)

  @GetMapping("/{relationId}/{mode:calendar|completed-workouts}")
  fun projection(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable relationId: UUID,
    @PathVariable mode: String,
    @RequestParam(defaultValue = "20") limit: Int,
    @RequestParam(required = false) cursor: String?,
  ) = service.projection(identity, relationId, mode, limit, cursor)
}
