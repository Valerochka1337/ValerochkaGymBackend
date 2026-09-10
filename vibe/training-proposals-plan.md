# PLAN-01 backend — training proposals

Slug: `training-proposals`. Status: T-001 accepted by root; ready for implementation. This is the server half of Android PLAN-01 for one recipient-approved, one-off routine plus calendar plan. It must remain compatible with the accepted strict fixture, pinned below.

## Goal, scope, non-goals, and assumptions

The server is the SSOT for proposal authorship, immutable versions, authorization, terminal decisions, accepted result, and idempotency. Under one database transaction, recipient approval writes one new personal `routine` record, one `calendar_plan` record, one owner-head revision, an approval receipt, and a proposal operation ledger entry.

Scope: internal AI creation; future coach authorization seam; recipient list/detail/approve/reject; author revoke; immutable snapshots; validation against the live canonical catalog, owner records, equipment and session; recovery by operation ID; migration and integration tests.

Non-goals: coach relationship invitation/persistence, public client proposal creation, direct coach writes, AI provider prompting/history selection, Google transport, Android Room projection/UI, recurring rules, and health/profile/notes exposure. `CoachJournalService` remains self-account workout-journal storage and cannot be a relation, authority, or proposal store.

Frozen assumptions: seven-day expiry, one plan per approval, future start at approval, 1..30 exercises and 1..20 sets each, server-generated result UUIDs, no automatic application, and `null` inferred weight only when AI has no same-exercise history. Explicit valid recipient/coach weights remain valid.

## Acceptance criteria

| ID | Server criterion |
|---|---|
| AC-B001 | Store proposal ID, source/author/recipient, immutable current and prior snapshots, expiry, timestamps, and `PENDING/APPROVED/REJECTED/REVOKED/STALE` state; AI has no user FK. |
| AC-B002 | Enforce participant authorization. Internal AI can create only for its authenticated recipient; `DefaultDenyCoachRelationAuthority` rejects every COACH capability until stage 23 replaces it. Session revocation is rechecked under lock. |
| AC-B003 | Validate every creation/edit/approval payload against the accepted fixture and locked canonical exercise/equipment/gym context; reject malformed, unknown, archived, unavailable, expired, stale, and active-workout cases before writes. |
| AC-B004 | Approve is exactly once: ledger key `(recipientId, operationId)` binds proposal/version/raw accepted request SHA-256. Exact retry returns the stored accepted result; changed bytes are `proposal_operation_conflict`; a new operation after approval returns that same result. |
| AC-B005 | All proposal mutations follow `catalog → recipient head → relation if COACH → proposal → session/revocation`, revalidate after locks, and atomically terminalize/create records/advance head/write receipt and ledger. |
| AC-B006 | Reject and revoke are authorized, idempotent for their version, terminal, and create no configuration records. Existing workout history and unrelated records are preserved. |
| AC-B007 | List/detail/accepted-result expose only the fixture projection to an authorized caller, use bounded opaque pagination, and never reveal profile, health, notes, prompts, credentials, or a hidden proposal’s existence. |

## Current → target flow and frozen contracts

Current `SyncService` already locks `catalog_state` then a recipient `sync_heads` row before it reads/writes JSONB `records`; it validates routine/calendar references and advances one owner revision. `SessionRepository.lock()` provides a session-revocation row lock. The changelog ends at `009-basic-profile.sql`.

```text
AI internal caller / future authorized coach
  → TrainingProposalService.createOrRevise (immutable snapshot)
  → recipient list/detail
  → raw approve body (hash before DTO conversion)
  → catalog lock → recipient head lock → coach authority lock → proposal lock → session lock
  → revalidate context, active workout, capability, snapshot/draft
  → records[routine, calendar_plan] + head revision + receipt + ledger + APPROVED
  → accepted result or exact ledger replay
```

Frozen contracts:

- Canonical fixture accepted in Android commit `ca41f1f`, SHA-256 `65254ebf9aaa4062ebf8ef71df99c76876685ce10ec0bd2fa8645fced56ea998`. Root copied identical bytes to `src/test/resources/training-proposals-contract.json`; both copies are immutable. T-001 is done; tests must consume the checked-in backend resource. Backend writer may not reinterpret the fixture.
- HTTP base and error matrix are exactly those in the fixture. A controller must capture and validate bounded raw bytes, hash before DTO deserialization, then pass bytes + typed request to the service. It must not hash a reserialized DTO.
- `routine` and `calendar_plan` are server-created owner `records` at the same new `sync_heads.revision`; their JSON payload shapes go through `RecordValidator` and its reference/archived checks over the locked aggregate. The plan uses new UUIDs, routine note `""`, derived exercise positions, `legacyScheduleId: null`, and no workout record.
- The capability header for approve must include accepted `calendar-plans`. The result revision is validation metadata, never a sync cursor.
- `DefaultDenyCoachRelationAuthority` is the only PLAN-01 coach authority implementation. It returns deny without looking at client input. A stage-23 implementation must own relationship persistence and provide the same interface/lock contract before COACH operations can be enabled.
- The existing unfinished, live owner workout (`records.kind='workout'`, not deleted, `finishedAt` null) is the active-workout guard. It is read only after the recipient head lock, which serializes concurrent sync mutations for that owner.

## Tasks

| ID | Owner / exact files | Depends on | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | Contract acceptance owner; read-only check of `/Users/raul/ItmoProjects/ValerochkaGym/vibe/contracts/training-proposals-contract.json`; record outcome only in this tracker | fixture writer’s accepted SHA; backend head `03a0c1ddc0deec53f30e8138b58eb85acc0edcfa` | Root accepted commit ca41f1f and pinned SHA; verify the identical backend test-resource copy and keep it immutable. | `shasum -a 256 /Users/raul/ItmoProjects/ValerochkaGym/vibe/contracts/training-proposals-contract.json` after accepted commit is known | Accepted fixture SHA and commit are recorded; no production work began against a moving schema. | AC-B001–B007 |
| T-002 | Sole backend writer: `src/main/resources/db/changelog/010-training-proposals.sql`, `src/main/resources/db/changelog/master.yaml`, `src/main/kotlin/tech/valerochkagym/repository/model/TrainingProposalEntities.kt`, `src/main/kotlin/tech/valerochkagym/repository/{data/SyncRepositories.kt,trainingproposal/{TrainingProposalRepositories.kt,TrainingProposalAccountCleanup.kt,TrainingProposalAuthorSnapshots.kt}}`, `src/main/kotlin/tech/valerochkagym/service/auth/AuthService.kt`, `src/main/kotlin/tech/valerochkagym/controller/model/TrainingProposalModels.kt`, `src/main/kotlin/tech/valerochkagym/service/trainingproposal/{TrainingProposalAuthority.kt,DefaultDenyCoachRelationAuthority.kt,TrainingProposalValidator.kt,TrainingProposalService.kt}`, `src/main/kotlin/tech/valerochkagym/service/ai/TrainingProposalAiCreator.kt`, `src/main/kotlin/tech/valerochkagym/controller/data/TrainingProposalController.kt`, `src/main/kotlin/tech/valerochkagym/service/data/RecordValidator.kt`, `src/test/kotlin/tech/valerochkagym/TrainingProposalIntegrationTest.kt` | T-001 | Add the next, append-only Liquibase changeset `codex:010-training-proposals`; proposal/version/receipt/operation tables and indexes stay together. Preserve a COACH audit UUID through an immutable author snapshot whose nullable live account pointer is detached only after confirmed account deletion and is never rebound. Implement DTO/raw-body boundary, internal-AI hook, default-deny coach seam, catalog/head/session locking and common mutation helper. Build and validate the complete locked record map, then atomically write routine+calendar records, head, receipt, ledger and proposal state. | `./gradlew --no-daemon test --tests '*TrainingProposalIntegrationTest'` | Fixture parity, all lifecycle routes, AI-only creation, recipient/author isolation, immutable coach audit identity, and a one-transaction approved result are proven. | AC-B001–B007 |
| T-003 | Same sole backend writer, T-002 files and `src/test/kotlin/tech/valerochkagym/TrainingProposalIntegrationTest.kt` only | stable T-002 | Add deterministic barrier/fault tests: double approve; same operation changed bytes; different operation after approve; response loss/replay; reject/revoke/version/expiry/context/session-revocation races; denied/missing/throwing coach authority; catalog/equipment/active-workout changes; rollback after every pre-commit fault; concurrent opposing operations for deadlock freedom. | `./gradlew --no-daemon test --tests '*TrainingProposalIntegrationTest'` | Every AC has a positive and relevant race/error assertion; count queries prove zero-or-one routine and plan and no unrelated history change. | AC-B002–B006 |
| T-004 | Independent backend tester/reviewer, read-only; updates only `vibe/training-proposals-plan-track.md` | T-002,T-003 stable | Audit fixture parity, raw-body hashing, lock order, expiry, SQL indexes/foreign keys, data leakage, terminal behavior, and test coverage. Run the project server gate once. | `./gradlew --no-daemon check bootJar` | No P0/P1 finding, or one consolidated finding packet naming file/AC/test. | AC-B001–B007 |
| T-005 | Original backend writer; production files named by findings and tracker | T-004 findings | Make one bounded correction pass; rerun only invalidated focused test, then the full gate if a production change remains. | affected `./gradlew --no-daemon test --tests '…'`; `./gradlew --no-daemon check bootJar` when changed | No open P0/P1; tracker contains command results, accepted fixture SHA, and residual risk. | affected AC |

## File ownership and execution waves

| Boundary | Owner | Rule |
|---|---|---|
| Strict fixture | fixture writer | Backend consumes the root-copied byte-identical test resource; no schema reinterpretation or local modifications. |
| Liquibase `010`, proposal entities/repositories, record validator, controller/DTO, service/AI hook, and integration tests | one backend writer | One vertical writer owns every shared transaction/auth/schema choke point. |
| Review and final gate | independent reviewer | Read-only until a consolidated finding is returned. |

Wave 0: T-001 is a hard gate. Wave 1: T-002 then T-003 by the same writer. Wave 2: T-004. Wave 3: only if needed, T-005. There are no parallel production implementers.

## Quality gates

Relevant gates: append-only Liquibase migration from production baseline; PostgreSQL transaction and lock-order race tests; raw request byte/idempotency tests; authorization/session revocation/privacy matrix; rollback and response-loss recovery; fixture parity; formatter/unit/integration/application build through `check bootJar`. No Android Gradle, Room, Hilt, permissions, manifest, WorkManager, dependency, UI, Google transport, or release signing gate belongs to this backend task.

## Risks, gaps, rollback, and data preservation

- T-001 accepted: Android ca41f1f and the pinned identical backend resource pass independent strict contract review; T-002 may start.
- **Coach gap:** no `coach_relationship` table exists. The safe default-deny implementation keeps every COACH route disabled; stage 23 must supply relationship storage and a lock-compatible authority before enabling it.
- **Catalog/sync coupling:** server-created records must use exactly the established payload shapes and revision semantics. T-002 must confirm the accepted CAL-01 predecessor supports `calendar_plan`; otherwise the task is blocked rather than inventing a payload.
- **Rollback:** do not roll back `010` in place after deployment. Preserve proposal/receipt/ledger and sync records; ship a forward migration or disable routes if remediation is required. No destructive deletes or fallback migration are permitted.

## Gate P self-check

PASS: every backend AC maps to T-001–T-005 and an observable command; one writer owns all mutable shared files; fixture, lock order, ledger, default-deny coach authority, session recheck, and atomic routine/calendar/head/receipt writes are frozen; only backend-relevant gates are listed. The fixture acceptance gate is complete.
