package tech.valerochkagym.service.trainingproposal

import java.util.UUID
import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.repository.coachrelation.CoachRelationRepositories

@Component
class DefaultDenyCoachRelationAuthority(private val relations: CoachRelationRepositories) :
  TrainingProposalAuthority {
  override fun requireCoachCapability(coachId: UUID, recipientId: UUID): Nothing = denied()

  override fun requireOriginCapability(coachId: UUID, recipientId: UUID, relationId: UUID?) {
    val r = relationId?.let { relations.relation(it, true) } ?: denied()
    if (
      r.coachId != coachId ||
        r.recipientId != recipientId ||
        r.state != "ACTIVE" ||
        r.liveCoachId != coachId ||
        r.liveRecipientId != recipientId
    )
      denied()
  }

  private fun denied(): Nothing =
    throw ApiException(403, "forbidden", "Связь с тренером недоступна")
}
