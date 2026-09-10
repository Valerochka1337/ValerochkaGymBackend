package tech.valerochkagym.repository.trainingproposal

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.service.model.Identity

/**
 * Immutable audit identity for a human coach proposal. A snapshot may be detached during account
 * deletion, but never rebound: authorization always comes from the live session and relation.
 */
@Repository
class TrainingProposalAuthorSnapshots(
  private val jdbc: JdbcTemplate,
  private val relations: tech.valerochkagym.repository.coachrelation.CoachRelationRepositories,
) {
  @Transactional
  fun bindAuthenticatedCoach(identity: Identity): UUID {
    relations.guards(identity.userId)
    relations.session(identity)
    jdbc.update(
      "INSERT INTO training_proposal_authors(historical_account_id,live_account_id,source) VALUES (?,?, 'COACH') ON CONFLICT (historical_account_id) DO NOTHING",
      identity.userId,
      identity.userId,
    )
    val liveAccount =
      jdbc
        .query(
          "SELECT live_account_id FROM training_proposal_authors WHERE historical_account_id=? FOR UPDATE",
          { rs, _ -> rs.getObject("live_account_id", UUID::class.java) },
          identity.userId,
        )
        .singleOrNull() ?: throw ApiException(403, "forbidden", "Автор предложения недоступен")
    if (liveAccount != identity.userId)
      throw ApiException(403, "forbidden", "Автор предложения недоступен")
    return identity.userId
  }

  fun detachLiveAccount(userId: UUID) {
    jdbc.update(
      "UPDATE training_proposal_authors SET live_account_id=NULL WHERE live_account_id=?",
      userId,
    )
  }
}
