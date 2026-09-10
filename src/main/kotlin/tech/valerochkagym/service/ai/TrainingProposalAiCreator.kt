package tech.valerochkagym.service.ai

import java.util.UUID
import org.springframework.stereotype.Service
import tech.valerochkagym.controller.model.ApprovalDraft
import tech.valerochkagym.controller.model.ProposalResponse
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.trainingproposal.TrainingProposalService

/** Typed internal-only entry point. HTTP clients cannot select the AI actor. */
@Service
class TrainingProposalAiCreator(private val proposals: TrainingProposalService) {
  fun createOrRevise(request: InternalAiProposalRequest): ProposalResponse =
    proposals.createOrReviseInternalAi(
      request.recipient,
      request.expectedOwnerRevision,
      request.expectedCatalogRevision,
      request.draft,
      request.proposalId,
    )

  fun createCalendar(request: InternalCalendarAiProposalRequest): ProposalResponse =
    proposals.createCalendarInternalAi(
      request.recipient,
      request.expectedOwnerRevision,
      request.expectedCatalogRevision,
      request.draft,
    )
}

data class InternalAiProposalRequest(
  val recipient: Identity,
  val expectedOwnerRevision: Long,
  val expectedCatalogRevision: Long,
  val draft: ApprovalDraft,
  val proposalId: UUID? = null,
)

data class InternalCalendarAiProposalRequest(
  val recipient: Identity,
  val expectedOwnerRevision: Long,
  val expectedCatalogRevision: Long,
  val draft: ApprovalDraft,
)
