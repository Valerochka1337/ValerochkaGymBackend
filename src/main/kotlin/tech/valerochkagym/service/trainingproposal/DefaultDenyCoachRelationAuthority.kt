package tech.valerochkagym.service.trainingproposal

import java.util.UUID
import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.ApiException

@Component
class DefaultDenyCoachRelationAuthority : TrainingProposalAuthority {
  override fun requireCoachCapability(coachId: UUID, recipientId: UUID): Nothing =
    throw ApiException(403, "forbidden", "Связь с тренером пока недоступна")
}
