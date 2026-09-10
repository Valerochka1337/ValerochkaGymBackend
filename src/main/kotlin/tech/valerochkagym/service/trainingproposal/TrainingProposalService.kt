package tech.valerochkagym.service.trainingproposal

import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.repository.auth.SessionRepository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.data.RecordRepository
import tech.valerochkagym.repository.model.*
import tech.valerochkagym.repository.trainingproposal.*
import tech.valerochkagym.service.data.RecordValidator
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.model.RecordKey
import tools.jackson.databind.ObjectMapper

@Service
class TrainingProposalService(
  private val catalog: CatalogStateRepository,
  private val standard: StandardRepository,
  private val heads: HeadRepository,
  private val records: RecordRepository,
  private val sessions: SessionRepository,
  private val proposals: TrainingProposalRepository,
  private val versions: TrainingProposalVersionRepository,
  private val receipts: TrainingProposalReceiptRepository,
  private val operations: TrainingProposalOperationRepository,
  private val authority: TrainingProposalAuthority,
  private val validator: TrainingProposalValidator,
  private val recordValidator: RecordValidator,
  private val json: ObjectMapper,
  private val tx: TransactionTemplate,
  private val clock: Clock,
  private val relations: tech.valerochkagym.repository.coachrelation.CoachRelationRepositories,
  private val relationService: tech.valerochkagym.service.coachrelation.CoachRelationsService,
  private val authors: TrainingProposalAuthorSnapshots,
) {
  private sealed interface ApprovalAttempt {
    data class Success(val result: AcceptedResult) : ApprovalAttempt

    data class Failure(val code: String) : ApprovalAttempt
  }

  fun createOrReviseInternalAi(
    identity: Identity,
    expectedOwnerRevision: Long,
    expectedCatalogRevision: Long,
    draft: ApprovalDraft,
    proposalId: UUID? = null,
  ): ProposalResponse {
    val normalized = validator.normalize(draft)
    return tx.execute {
      relations.guards(identity.userId)
      val catalogHead = catalog.readLock()
      val ownerHead = head(identity.userId)
      if (
        catalogHead.revision != expectedCatalogRevision ||
          ownerHead.revision != expectedOwnerRevision
      )
        throw ApiException(409, "proposal_stale", "Контекст предложения изменился")
      val existing = proposalId?.let { proposals.writeLock(it) ?: hidden() }
      if (
        existing != null &&
          (existing.recipientId != identity.userId || existing.source != TrainingProposalSource.AI)
      )
        hidden()
      lockSession(identity)
      val now = clock.instant()
      val aiDraft = aiDraftWithHistoryWeights(normalized, identity.userId)
      validator.validateDraft(aiDraft, now)
      validateLiveDraft(aiDraft, identity.userId, now)
      val entity =
        existing
          ?: proposals.saveAndFlush(
            TrainingProposalEntity(
              recipientId = identity.userId,
              source = TrainingProposalSource.AI,
              status = TrainingProposalStatus.PENDING,
              currentVersion = 1,
              createdAt = now,
              updatedAt = now,
              expiresAt = now.plusSeconds(7 * 24 * 60 * 60),
            )
          )
      if (existing != null) {
        if (entity.status != TrainingProposalStatus.PENDING) error("proposal_stale")
        entity.currentVersion += 1
        entity.updatedAt = now
      }
      val version =
        TrainingProposalVersionEntity(
          proposalId = entity.id,
          version = entity.currentVersion,
          draft = json.writeValueAsString(aiDraft),
          ownerRevision = ownerHead.revision,
          catalogRevision = catalogHead.revision,
          createdAt = now,
        )
      versions.save(version)
      proposal(entity, version)
    }
  }

  /** Calendar has a separate typed internal entry point; no HTTP actor can select it. */
  fun createCalendarInternalAi(
    identity: Identity,
    expectedOwnerRevision: Long,
    expectedCatalogRevision: Long,
    draft: ApprovalDraft,
  ): ProposalResponse {
    val normalized = validator.normalize(draft)
    return tx.execute {
      // This path intentionally materializes only draft dependencies. Do not replace it with the
      // legacy creator: that path has owner-wide history and live-draft scans.
      relations.guards(identity.userId)
      val catalogHead = catalog.readLock()
      val ownerHead = head(identity.userId)
      if (
        catalogHead.revision != expectedCatalogRevision ||
          ownerHead.revision != expectedOwnerRevision
      )
        throw ApiException(409, "proposal_stale", "Контекст предложения изменился")
      lockSession(identity)
      val now = clock.instant()
      validator.validateDraft(normalized, now)
      validateCalendarLiveDraft(normalized, identity.userId, catalogHead.active, now)
      val entity =
        proposals.saveAndFlush(
          TrainingProposalEntity(
            recipientId = identity.userId,
            source = TrainingProposalSource.AI,
            status = TrainingProposalStatus.PENDING,
            currentVersion = 1,
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plusSeconds(7 * 24 * 60 * 60),
          )
        )
      val version =
        TrainingProposalVersionEntity(
          proposalId = entity.id,
          version = 1,
          draft = json.writeValueAsString(normalized),
          ownerRevision = ownerHead.revision,
          catalogRevision = catalogHead.revision,
          createdAt = now,
        )
      versions.save(version)
      proposal(entity, version)
    }!!
  }

  private fun validateCalendarLiveDraft(
    draft: ApprovalDraft,
    owner: UUID,
    catalogActive: Boolean,
    now: Instant,
  ) {
    if (records.hasActiveWorkout(owner))
      throw ApiException(409, "active_workout", "Сначала завершите текущую тренировку")
    val exerciseIds = draft.exercises.map { UUID.fromString(it.exerciseId) }.distinct()
    val gymIds = draft.gymIds.map(UUID::fromString).distinct()
    // Each query has a finite request-derived IN list (at most 12 exercises / 1000 gyms).
    val personalExercises =
      records.findByUserIdAndKindAndIdInAndDeletedFalse(owner, "exercise", exerciseIds)
    val personalGyms = records.findByUserIdAndKindAndIdInAndDeletedFalse(owner, "gym", gymIds)
    val standardExercises =
      if (catalogActive) standard.findByKindAndIdInAndArchivedFalse("exercise", exerciseIds)
      else emptyList()
    val standardGyms =
      if (catalogActive) standard.findByKindAndIdInAndArchivedFalse("gym", gymIds) else emptyList()
    val aggregate = linkedMapOf<RecordKey, Record>()
    (personalExercises + personalGyms).forEach { row ->
      aggregate[RecordKey(row.kind, row.id)] =
        Record(row.kind, row.id, row.revision, row.deleted, row.payload?.let(json::readTree))
    }
    (standardExercises + standardGyms).forEach { row ->
      aggregate[RecordKey(row.kind, row.id)] =
        Record(row.kind, row.id, row.revision, false, json.readTree(row.payload))
    }
    draft.exercises.forEach { exercise ->
      val record =
        aggregate[RecordKey("exercise", UUID.fromString(exercise.exerciseId))]
          ?: bad("Упражнение недоступно")
      val type = record.payload!!["type"]?.asString() ?: bad("Упражнение недоступно")
      exercise.plannedSets.forEach { validator.plannedSet(it, type) }
    }
    draft.gymIds.forEach { id ->
      if (aggregate[RecordKey("gym", UUID.fromString(id))] == null) bad("Зал недоступен")
    }
    val routineId = UUID.randomUUID()
    val planId = UUID.randomUUID()
    val routine = routinePayload(draft, now)
    val plan = calendarPlanPayload(routineId, draft)
    recordValidator.validate("routine", routine)
    recordValidator.validate("calendar_plan", plan)
    val candidate = aggregate.toMutableMap()
    candidate[RecordKey("routine", routineId)] = Record("routine", routineId, 0, false, routine)
    candidate[RecordKey("calendar_plan", planId)] = Record("calendar_plan", planId, 0, false, plan)
    recordValidator.references(
      candidate,
      setOf(RecordKey("routine", routineId), RecordKey("calendar_plan", planId)),
      aggregate,
    )
    recordValidator.archivedReferences(candidate, aggregate, emptySet())
  }

  fun mutateCoach(
    identity: Identity,
    relationId: UUID,
    proposalId: UUID?,
    raw: ByteArray,
    action: String,
  ): Any {
    val fields =
      when (action) {
        "CREATE_COACH_PROPOSAL" ->
          setOf("operationId", "expectedOwnerRevision", "expectedCatalogRevision", "draft")
        "REVISE_COACH_PROPOSAL" -> setOf("operationId", "expectedVersion", "draft")
        else -> setOf("operationId", "expectedVersion")
      }
    val request = relationService.body(raw, fields)
    val operation = relationService.uuid(request, "operationId")
    val route =
      when (action) {
        "CREATE_COACH_PROPOSAL" -> "POST /v1/coach-relations/{relationId}/training-proposals"
        "REVISE_COACH_PROPOSAL" ->
          "PUT /v1/coach-relations/{relationId}/training-proposals/{proposalId}"
        else -> "POST /v1/coach-relations/{relationId}/training-proposals/{proposalId}/revoke"
      }
    val tuple = listOfNotNull(relationId, proposalId).joinToString(",")
    relationService.preliminaryBinding(identity, operation, action, route, raw, tuple)
    val known =
      relations.relation(relationId)
        ?: throw ApiException(404, "relation_not_found", "Связь недоступна")
    if (known.coachId != identity.userId)
      throw ApiException(404, "relation_not_found", "Связь недоступна")
    return tx.execute {
      relations.guards(known.coachId, known.recipientId)
      val catalogHead = catalog.readLock()
      val ownerHead = head(known.recipientId)
      val relation = relations.relation(relationId, true) ?: hidden()
      val existing = proposalId?.let { proposals.writeLock(it) ?: hidden() }
      if (
        existing != null &&
          (existing.originRelationId != relationId ||
            existing.authorId != identity.userId ||
            existing.recipientId != relation.recipientId ||
            existing.source != TrainingProposalSource.COACH)
      )
        hidden()
      relations.session(identity)
      relationService.replay(identity, operation, action, route, tuple, raw)?.let { replay ->
        if (action == "REVOKE_COACH_PROPOSAL") return@execute replay
      }
      requireOriginCapability(identity.userId, known.recipientId, relationId)
      fun integer(key: String): Long {
        val value = request[key]
        if (
          !value.isIntegralNumber ||
            !value.canConvertToLong() ||
            value.asLong() < (if (key == "expectedVersion") 1 else 0)
        )
          bad("Некорректная версия")
        return value.asLong()
      }
      relationService.replay(identity, operation, action, route, tuple, raw)?.let {
        return@execute it
      }
      if (existing != null && integer("expectedVersion") != existing.currentVersion.toLong())
        error("proposal_version_conflict")
      if (action == "REVOKE_COACH_PROPOSAL") {
        val entity = existing ?: hidden()
        if (entity.status != TrainingProposalStatus.PENDING) error("proposal_stale")
        entity.status = TrainingProposalStatus.REVOKED
        entity.updatedAt = clock.instant()
        val result = decision(entity)
        relationService.save(identity, operation, action, route, tuple, raw, result)
        return@execute result
      }
      // Exact successful create/revise replay still requires live relation; no duplicate version.
      relationService.replay(identity, operation, action, route, tuple, raw)?.let {
        return@execute it
      }
      if (
        existing == null &&
          (integer("expectedOwnerRevision") != ownerHead.revision ||
            integer("expectedCatalogRevision") != catalogHead.revision)
      )
        error("proposal_stale")
      if (existing != null && existing.status != TrainingProposalStatus.PENDING)
        error("proposal_stale")
      val now = clock.instant()
      val draft = validator.normalize(validator.draft(request["draft"]))
      validator.validateDraft(draft, now)
      validateLiveDraft(draft, known.recipientId, now)
      val author = authors.bindAuthenticatedCoach(identity)
      val entity =
        existing
          ?: proposals.saveAndFlush(
            TrainingProposalEntity(
              recipientId = known.recipientId,
              authorId = author,
              originRelationId = relationId,
              source = TrainingProposalSource.COACH,
              status = TrainingProposalStatus.PENDING,
              currentVersion = 1,
              createdAt = now,
              updatedAt = now,
              expiresAt = now.plusSeconds(604800),
            )
          )
      if (existing != null) {
        entity.currentVersion++
        entity.updatedAt = now
      }
      val version =
        TrainingProposalVersionEntity(
          proposalId = entity.id,
          version = entity.currentVersion,
          originRelationId = relationId,
          draft = json.writeValueAsString(draft),
          ownerRevision = ownerHead.revision,
          catalogRevision = catalogHead.revision,
          createdAt = now,
        )
      versions.saveAndFlush(version)
      val result = proposal(entity, version)
      relationService.save(identity, operation, action, route, tuple, raw, result)
      result
    }
  }

  fun list(identity: Identity, limit: Int, cursor: String?): ProposalListResponse {
    if (limit !in 1..50) bad("Некорректный размер страницы")
    val before = cursor?.let { cursor(identity.userId, it) } ?: Long.MAX_VALUE
    val page =
      proposals.findByRecipientIdAndCreatedSequenceLessThanOrderByCreatedSequenceDesc(
        identity.userId,
        before,
        PageRequest.of(0, limit + 1),
      )
    val emitted = mutableListOf<Pair<TrainingProposalEntity, ProposalResponse>>()
    for ((index, entity) in page.take(limit).withIndex()) {
      val item = proposal(entity, currentVersion(entity))
      val candidate =
        ProposalListResponse(
          emitted.map { it.second } + item,
          entity.id.takeIf { index + 1 < page.size }?.let(::cursor),
        )
      if (json.writeValueAsBytes(candidate).size > maxListResponseBytes) {
        if (emitted.isEmpty())
          throw ApiException(413, "payload_too_large", "Предложение слишком велико для списка")
        break
      }
      emitted += entity to item
    }
    val hasMore = page.size > emitted.size
    return ProposalListResponse(
      emitted.map { it.second },
      emitted.lastOrNull()?.first?.id?.takeIf { hasMore }?.let(::cursor),
    )
  }

  fun detail(identity: Identity, proposalId: UUID): ProposalResponse {
    val proposal = proposals.findById(proposalId).orElse(null) ?: hidden()
    if (proposal.recipientId != identity.userId) hidden()
    return proposal(proposal, currentVersion(proposal))
  }

  fun acceptedResult(identity: Identity, proposalId: UUID): AcceptedResult {
    val proposal = proposals.findById(proposalId).orElse(null) ?: hidden()
    if (proposal.recipientId != identity.userId) hidden()
    return receipt(proposal.id, proposal.currentVersion)
      ?: throw ApiException(409, "proposal_not_approved", "Предложение ещё не принято")
  }

  fun approve(
    identity: Identity,
    proposalId: UUID,
    raw: ByteArray,
    requestSha256: String,
    request: ApprovalRequest,
  ): AcceptedResult {
    val attempt =
      tx.execute {
        // Read the immutable participant tuple solely to acquire the recipient/relation locks in
        // order. The source/author/recipient tuple and relation capability are checked again
        // after the proposal row lock, before session validation or any state decision.
        val known = proposals.findById(proposalId).orElse(null) ?: hidden()
        if (known.recipientId != identity.userId) hidden()
        if (known.source == TrainingProposalSource.COACH)
          relations.guards(known.authorId ?: error("proposal_stale"), known.recipientId)
        else relations.guards(known.recipientId)
        val catalogHead = catalog.readLock()
        val ownerHead = head(known.recipientId)
        if (known.source == TrainingProposalSource.COACH)
          known.originRelationId?.let { relations.relation(it, true) }
        val proposal = proposals.writeLock(proposalId) ?: hidden()
        if (
          proposal.recipientId != identity.userId ||
            proposal.source != known.source ||
            proposal.authorId != known.authorId ||
            proposal.originRelationId != known.originRelationId
        )
          hidden()
        lockSession(identity)
        val operation =
          operations
            .findById(
              TrainingProposalOperationId(identity.userId, UUID.fromString(request.operationId))
            )
            .orElse(null)
        if (operation != null) {
          if (
            operation.proposalId != proposal.id ||
              operation.version != request.version ||
              operation.requestSha256 != requestSha256
          )
            throw ApiException(
              409,
              "proposal_operation_conflict",
              "Операция уже связана с другим запросом",
            )
          return@execute ApprovalAttempt.Success(
            receipt(operation.proposalId, operation.version) ?: error("proposal_not_approved")
          )
        }
        val version = version(proposal.id, proposal.currentVersion)
        if (request.version != proposal.currentVersion)
          return@execute ApprovalAttempt.Failure("proposal_version_conflict")
        when (proposal.status) {
          TrainingProposalStatus.APPROVED -> {
            val accepted =
              receipt(proposal.id, proposal.currentVersion) ?: error("proposal_not_approved")
            operations.save(
              TrainingProposalOperationEntity(
                recipientId = identity.userId,
                operationId = UUID.fromString(request.operationId),
                proposalId = proposal.id,
                version = request.version,
                requestSha256 = requestSha256,
                rawRequest = raw.copyOf(),
                createdAt = clock.instant(),
              )
            )
            return@execute ApprovalAttempt.Success(accepted)
          }
          TrainingProposalStatus.REJECTED ->
            return@execute ApprovalAttempt.Failure("proposal_rejected")
          TrainingProposalStatus.REVOKED ->
            return@execute ApprovalAttempt.Failure("proposal_revoked")
          TrainingProposalStatus.STALE -> return@execute ApprovalAttempt.Failure("proposal_stale")
          TrainingProposalStatus.PENDING -> Unit
        }
        if (proposal.source == TrainingProposalSource.COACH)
          requireOriginCapability(
            proposal.authorId ?: error("proposal_stale"),
            proposal.recipientId,
            proposal.originRelationId,
          )
        val now = clock.instant()
        if (!proposal.expiresAt.isAfter(now))
          return@execute stale(proposal, now, "proposal_expired")
        if (
          catalogHead.revision != version.catalogRevision ||
            ownerHead.revision != version.ownerRevision
        )
          return@execute stale(proposal, now, "proposal_stale")
        // The author snapshot remains immutable. A recipient may approve a fresh local edit;
        // proposal_operations retains the exact raw accepted bytes for replay and audit.
        validator.validateDraft(request.draft, now)
        val live =
          try {
            validateLiveDraft(request.draft, identity.userId, now)
          } catch (e: ApiException) {
            return@execute if (e.code == "active_workout") ApprovalAttempt.Failure("active_workout")
            else stale(proposal, now, "proposal_stale")
          }
        val revision = ownerHead.revision + 1
        val routineId = UUID.randomUUID()
        val calendarPlanId = UUID.randomUUID()
        val routine = routinePayload(request.draft, now)
        val plan = calendarPlanPayload(routineId, request.draft)
        writeApprovedRecords(
          identity.userId,
          revision,
          routineId,
          calendarPlanId,
          routine,
          plan,
          live,
        )
        ownerHead.revision = revision
        proposal.status = TrainingProposalStatus.APPROVED
        proposal.updatedAt = now
        val accepted =
          AcceptedResult(
            proposal.id,
            proposal.currentVersion,
            routineId,
            calendarPlanId,
            revision,
            now.toEpochMilli(),
          )
        receipts.save(
          TrainingProposalReceiptEntity(
            proposalId = proposal.id,
            version = proposal.currentVersion,
            routineId = routineId,
            calendarPlanId = calendarPlanId,
            revision = revision,
            approvedAt = now,
          )
        )
        operations.save(
          TrainingProposalOperationEntity(
            recipientId = identity.userId,
            operationId = UUID.fromString(request.operationId),
            proposalId = proposal.id,
            version = proposal.currentVersion,
            requestSha256 = requestSha256,
            rawRequest = raw.copyOf(),
            createdAt = now,
          )
        )
        ApprovalAttempt.Success(accepted)
      }
    return when (attempt) {
      is ApprovalAttempt.Success -> attempt.result
      is ApprovalAttempt.Failure -> error(attempt.code)
    }
  }

  fun reject(identity: Identity, proposalId: UUID, request: RejectRequest): DecisionResponse =
    tx.execute {
      relations.guards(identity.userId)
      catalog.readLock()
      head(identity.userId)
      val proposal = proposals.writeLock(proposalId) ?: hidden()
      if (proposal.recipientId != identity.userId) hidden()
      lockSession(identity)
      if (request.version != proposal.currentVersion) error("proposal_version_conflict")
      val now = clock.instant()
      if (proposal.status == TrainingProposalStatus.REJECTED) return@execute decision(proposal)
      if (proposal.status == TrainingProposalStatus.REVOKED) error("proposal_revoked")
      if (proposal.status == TrainingProposalStatus.APPROVED) error("proposal_stale")
      if (proposal.status == TrainingProposalStatus.STALE || !proposal.expiresAt.isAfter(now))
        error("proposal_expired")
      proposal.status = TrainingProposalStatus.REJECTED
      proposal.updatedAt = now
      decision(proposal)
    }

  /** Legacy PLAN-01 has no operation ID; coach mutations use the relation-scoped ledger routes. */
  fun revoke(identity: Identity, proposalId: UUID, request: RevokeRequest): DecisionResponse =
    throw ApiException(403, "forbidden", "Используйте отзыв через связь с тренером")

  private fun requireOriginCapability(coach: UUID, recipient: UUID, relation: UUID?) {
    try {
      authority.requireOriginCapability(coach, recipient, relation)
    } catch (e: ApiException) {
      throw e
    } catch (_: Exception) {
      throw ApiException(403, "forbidden", "Связь недоступна")
    }
  }

  private fun head(user: UUID): HeadEntity {
    return heads.writeLockOrNull(user) ?: unauthorized()
  }

  private fun lockSession(identity: Identity) {
    val row = sessions.lock(identity.sessionId) ?: unauthorized()
    if (
      row.userId != identity.userId ||
        row.revokedAt != null ||
        !row.accessExpiresAt.isAfter(clock.instant()) ||
        !row.refreshExpiresAt.isAfter(clock.instant())
    )
      unauthorized()
  }

  private fun currentVersion(proposal: TrainingProposalEntity) =
    version(proposal.id, proposal.currentVersion)

  private fun version(proposalId: UUID, version: Int) =
    versions.findById(TrainingProposalVersionId(proposalId, version)).orElseThrow {
      IllegalStateException("Proposal version is missing")
    }

  private fun draft(version: TrainingProposalVersionEntity) =
    validator.draft(json.readTree(version.draft))

  private fun proposal(entity: TrainingProposalEntity, version: TrainingProposalVersionEntity) =
    ProposalResponse(
      entity.id,
      ProposalAuthor(entity.source.name, entity.authorId),
      entity.recipientId,
      entity.source.name,
      entity.status.name,
      entity.currentVersion,
      entity.createdAt.toEpochMilli(),
      entity.updatedAt.toEpochMilli(),
      entity.expiresAt.toEpochMilli(),
      ProposalSnapshot(
        version.version,
        draft(version),
        version.ownerRevision,
        version.catalogRevision,
        version.createdAt.toEpochMilli(),
      ),
    )

  private fun receipt(proposalId: UUID, version: Int): AcceptedResult? =
    receipts.findById(TrainingProposalReceiptId(proposalId, version)).orElse(null)?.let {
      AcceptedResult(
        it.proposalId,
        it.version,
        it.routineId,
        it.calendarPlanId,
        it.revision,
        it.approvedAt.toEpochMilli(),
      )
    }

  private fun decision(proposal: TrainingProposalEntity) =
    DecisionResponse(
      proposal.id,
      proposal.currentVersion,
      proposal.status.name,
      proposal.updatedAt.toEpochMilli(),
    )

  private fun stale(
    proposal: TrainingProposalEntity,
    now: Instant,
    code: String,
  ): ApprovalAttempt.Failure {
    proposal.status = TrainingProposalStatus.STALE
    proposal.updatedAt = now
    return ApprovalAttempt.Failure(code)
  }

  private fun validateLiveDraft(
    draft: ApprovalDraft,
    owner: UUID,
    now: Instant,
  ): Map<RecordKey, Record> {
    val personal =
      records.findByUserIdOrderByKindAscIdAsc(owner).map {
        Record(it.kind, it.id, it.revision, it.deleted, it.payload?.let(json::readTree))
      }
    if (
      personal.any {
        it.kind == "workout" &&
          !it.deleted &&
          (it.payload?.get("finishedAt") == null || it.payload["finishedAt"].isNull)
      }
    )
      throw ApiException(409, "active_workout", "Сначала завершите текущую тренировку")
    val commonRows = standard.findAllByOrderByKindAscIdAsc()
    val common =
      commonRows.associate {
        RecordKey(it.kind, it.id) to
          Record(it.kind, it.id, it.revision, false, json.readTree(it.payload))
      }
    val aggregate =
      personal.associateBy { RecordKey(it.kind, it.id) }.toMutableMap().apply { putAll(common) }
    val types =
      commonRows
        .filter { it.kind == "exercise" && !it.archived }
        .associate { it.id to json.readTree(it.payload)["type"].asString() }
        .toMutableMap()
    personal
      .filter { it.kind == "exercise" && !it.deleted && it.payload != null }
      .forEach { types[it.id] = it.payload!!["type"].asString() }
    draft.exercises.forEach { exercise ->
      val type = types[UUID.fromString(exercise.exerciseId)] ?: bad("Упражнение недоступно")
      exercise.plannedSets.forEach { validator.plannedSet(it, type) }
    }
    draft.gymIds.forEach { id ->
      val record = aggregate[RecordKey("gym", UUID.fromString(id))]
      if (record?.deleted != false) bad("Зал недоступен")
    }
    val syntheticRoutine = UUID.randomUUID()
    val syntheticPlan = UUID.randomUUID()
    val candidate = aggregate.toMutableMap()
    val routine = routinePayload(draft, now)
    val plan = calendarPlanPayload(syntheticRoutine, draft)
    recordValidator.validate("routine", routine)
    recordValidator.validate("calendar_plan", plan)
    candidate[RecordKey("routine", syntheticRoutine)] =
      Record("routine", syntheticRoutine, 0, false, routine)
    candidate[RecordKey("calendar_plan", syntheticPlan)] =
      Record("calendar_plan", syntheticPlan, 0, false, plan)
    recordValidator.references(
      candidate,
      setOf(RecordKey("routine", syntheticRoutine), RecordKey("calendar_plan", syntheticPlan)),
      aggregate,
    )
    val archived =
      standard
        .findAllByOrderByKindAscIdAsc()
        .filter { it.archived }
        .map { RecordKey(it.kind, it.id) }
        .toSet()
    recordValidator.archivedReferences(candidate, aggregate, archived)
    return aggregate
  }

  private fun aiDraftWithHistoryWeights(draft: ApprovalDraft, owner: UUID): ApprovalDraft {
    val completedExercises =
      records
        .findByUserIdOrderByKindAscIdAsc(owner)
        .filter { it.kind == "workout" && !it.deleted && it.payload != null }
        .filter {
          json.readTree(it.payload)["finishedAt"]?.takeUnless { value -> value.isNull } != null
        }
        .flatMap { row -> json.readTree(row.payload)["exercises"]?.toList().orEmpty() }
        .mapNotNull { it["exerciseId"]?.asString() }
        .toSet()
    return draft.copy(
      exercises =
        draft.exercises.map { exercise ->
          if (exercise.exerciseId in completedExercises) exercise
          else exercise.copy(plannedSets = exercise.plannedSets.map { it.copy(weightKg = null) })
        }
    )
  }

  private fun writeApprovedRecords(
    owner: UUID,
    revision: Long,
    routineId: UUID,
    planId: UUID,
    routine: tools.jackson.databind.JsonNode,
    plan: tools.jackson.databind.JsonNode,
    before: Map<RecordKey, Record>,
  ) {
    recordValidator.validate("routine", routine)
    recordValidator.validate("calendar_plan", plan)
    val next = before.toMutableMap()
    val routineRecord = Record("routine", routineId, revision, false, routine)
    val planRecord = Record("calendar_plan", planId, revision, false, plan)
    next[RecordKey("routine", routineId)] = routineRecord
    next[RecordKey("calendar_plan", planId)] = planRecord
    recordValidator.references(
      next,
      setOf(RecordKey("routine", routineId), RecordKey("calendar_plan", planId)),
      before,
    )
    val archived =
      standard
        .findAllByOrderByKindAscIdAsc()
        .filter { it.archived }
        .map { RecordKey(it.kind, it.id) }
        .toSet()
    recordValidator.archivedReferences(next, before, archived)
    records.save(
      RecordEntity(owner, "routine", routineId, revision, false, json.writeValueAsString(routine))
    )
    records.save(
      RecordEntity(owner, "calendar_plan", planId, revision, false, json.writeValueAsString(plan))
    )
  }

  private fun routinePayload(draft: ApprovalDraft, now: Instant) =
    json.valueToTree<tools.jackson.databind.JsonNode>(
      mapOf(
        "name" to draft.name,
        "note" to "",
        "updatedAt" to now.toEpochMilli(),
        "gymIds" to draft.gymIds,
        "exercises" to
          draft.exercises.mapIndexed { index, item ->
            mapOf(
              "exerciseId" to item.exerciseId,
              "position" to index,
              "restSeconds" to item.restSeconds,
              "plannedSets" to item.plannedSets,
            )
          },
      )
    )

  private fun calendarPlanPayload(routineId: UUID, draft: ApprovalDraft) =
    json.valueToTree<tools.jackson.databind.JsonNode>(
      mapOf(
        "routineId" to routineId.toString(),
        "startsAtMillis" to draft.startsAtMillis,
        "timeZoneId" to draft.timeZoneId,
        "legacyScheduleId" to null,
      )
    )

  private fun cursor(proposalId: UUID) =
    Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(proposalId.toString().toByteArray(StandardCharsets.US_ASCII))

  private fun cursor(recipientId: UUID, raw: String): Long =
    try {
      val decoded = Base64.getUrlDecoder().decode(raw).toString(StandardCharsets.US_ASCII)
      val proposalId =
        UUID.fromString(decoded).takeIf { it.toString() == decoded }
          ?: throw IllegalArgumentException()
      proposals
        .findById(proposalId)
        .orElse(null)
        ?.takeIf { it.recipientId == recipientId }
        ?.createdSequence ?: throw IllegalArgumentException()
    } catch (_: Exception) {
      bad("Некорректный курсор")
    }

  private fun hidden(): Nothing =
    throw ApiException(404, "proposal_not_found", "Предложение не найдено")

  private fun error(code: String): Nothing =
    throw ApiException(409, code, "Предложение нельзя обработать в текущем состоянии")

  private companion object {
    const val maxListResponseBytes = 1024 * 1024
  }
}
