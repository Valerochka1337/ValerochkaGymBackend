package tech.valerochkagym.repository.trainingproposal

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.data.HeadRepository

/**
 * Deletes only proposal journal rows owned by an already-confirmed recipient deletion. Approval
 * records are ordinary owner sync records and remain untouched here; user deletion removes them
 * through their existing owner FK. Authored COACH proposals are locked and pending ones
 * terminalized; accepted proposals and receipts of other recipients survive with detached author
 * snapshots.
 */
@Repository
class TrainingProposalAccountCleanup(
  private val catalog: CatalogStateRepository,
  private val heads: HeadRepository,
  private val jdbc: JdbcTemplate,
  private val relations: tech.valerochkagym.repository.coachrelation.CoachRelationRepositories,
) {
  /** Locks the proposal-mutator prefix without materializing a head for an invalid delete code. */
  fun preflightAccountDeletion(userId: UUID) {
    relations.guards(userId)
    val affected =
      jdbc.query(
        "SELECT recipient_id FROM coach_relations WHERE coach_id=? OR recipient_id=? UNION SELECT recipient_id FROM training_proposals WHERE author_id=?",
        { rs, _ -> rs.getObject(1, UUID::class.java) },
        userId,
        userId,
        userId,
      )
    catalog.readLock()
    (affected + userId).distinct().sortedBy(UUID::toString).forEach { heads.writeLockOrNull(it) }
    jdbc.query(
      "SELECT id FROM coach_relations WHERE coach_id=? OR recipient_id=? ORDER BY id FOR UPDATE",
      { _, _ -> Unit },
      userId,
      userId,
    )
    jdbc.query(
      "SELECT id FROM training_proposals WHERE recipient_id=? OR author_id=? ORDER BY id FOR UPDATE",
      { _, _ -> Unit },
      userId,
      userId,
    )
  }

  /** Requires [preflightAccountDeletion] in the containing transaction. */
  fun removeRecipientLocked(userId: UUID) {
    val counterparts =
      jdbc
        .query(
          "SELECT coach_id,recipient_id FROM coach_relations WHERE (coach_id=? OR recipient_id=?) AND state='ACTIVE'",
          { rs, _ -> listOf(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java)) },
          userId,
          userId,
        )
        .flatten()
        .distinct()
    jdbc.update(
      "UPDATE coach_relations SET state='REVOKED',revoked_at=COALESCE(revoked_at,now()),live_coach_id=CASE WHEN coach_id=? THEN NULL ELSE live_coach_id END,live_recipient_id=CASE WHEN recipient_id=? THEN NULL ELSE live_recipient_id END WHERE coach_id=? OR recipient_id=?",
      userId,
      userId,
      userId,
      userId,
    )
    relations.advance(*counterparts.toTypedArray())
    jdbc.update(
      "UPDATE coach_relation_invitations SET live_coach_id=CASE WHEN coach_id=? THEN NULL ELSE live_coach_id END,live_consumed_by=CASE WHEN consumed_by=? THEN NULL ELSE live_consumed_by END WHERE coach_id=? OR consumed_by=?",
      userId,
      userId,
      userId,
      userId,
    )
    jdbc.update(
      "UPDATE training_proposals SET status='REVOKED',updated_at=now() WHERE source='COACH' AND status='PENDING' AND (author_id=? OR recipient_id=?)",
      userId,
      userId,
    )
    jdbc.update("DELETE FROM coach_relation_operations WHERE actor_id=?", userId)
    jdbc.update("DELETE FROM training_proposal_operations WHERE recipient_id=?", userId)
    jdbc.update("DELETE FROM calendar_ai_attempts WHERE owner_id=?", userId)
    jdbc.update(
      "DELETE FROM training_proposal_receipts WHERE proposal_id IN (SELECT id FROM training_proposals WHERE recipient_id=?)",
      userId,
    )
    jdbc.update(
      "DELETE FROM training_proposal_versions WHERE proposal_id IN (SELECT id FROM training_proposals WHERE recipient_id=?)",
      userId,
    )
    jdbc.update("DELETE FROM training_proposals WHERE recipient_id=?", userId)
  }
}
