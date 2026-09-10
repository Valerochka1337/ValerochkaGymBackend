package tech.valerochkagym.service.trainingproposal

import java.util.UUID

/**
 * Stage 23 replaces this seam with relationship persistence that holds its lock before proposal.
 */
interface TrainingProposalAuthority {
  fun requireCoachCapability(coachId: UUID, recipientId: UUID)
}
