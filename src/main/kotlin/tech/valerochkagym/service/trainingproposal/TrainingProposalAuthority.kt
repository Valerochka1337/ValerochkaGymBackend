package tech.valerochkagym.service.trainingproposal

import java.util.UUID

/**
 * Stage 23 replaces this seam with relationship persistence that holds its lock before proposal.
 */
interface TrainingProposalAuthority {
  fun requireCoachCapability(coachId: UUID, recipientId: UUID)

  fun requireOriginCapability(coachId: UUID, recipientId: UUID, relationId: UUID?) {
    if (relationId == null)
      throw tech.valerochkagym.controller.advice.ApiException(403, "forbidden", "Связь недоступна")
    requireCoachCapability(coachId, recipientId)
  }
}
