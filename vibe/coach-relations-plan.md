# Stage 23 backend — coach relations

Slug: `coach-relations`. Status: strict Gate P repair draft. One backend writer owns invitations, consented relations, bounded coach reads, and live PLAN-01 COACH authority. It never reuses `/sync`, `/records`, `coach_journal`, or AI context.

## Goal, scope, non-goals, assumptions

The backend is SSOT for invite consumption, both explicit consent flags, relation liveness, directory/relation revisions, projection cursors and COACH proposal authority. Invite delivery is manual: no email/UUID search or server message. Its token is 32 CSPRNG bytes, canonical 43-character unpadded base64url, valid for seven server-time days, and stored only as a purpose/version-separated HMAC digest. Multiple pairs may be active, but a second invite to an active pair is `409 relation_exists` and cannot alter grants.

Scope: invite create/accept/replay/expiry, bilateral revoke, client/coach directories, calendar and completed-workout projections, COACH proposal create/revise/revoke, pending apply, deletion, migration, fixture and PostgreSQL tests. Non-goals: profile, health, measurements, notes/cues, active workouts, AI, automatic apply, Android/Room/UI and resolving PLAN-01 edited-preview equality. Android retains brief AC-007/AC-008 owner-switch/local/UI work; backend owns bounded owner/relation cursor IDs.

## Acceptance criteria

| ID | Server criterion |
|---|---|
| AC-001 | Non-self authenticated recipient explicitly submits both booleans to consume a seven-day one-use invite; no account discovery; same recipient/same grants replay safely and another recipient cannot consume it. |
| AC-002 | ACTIVE relation stores independent calendar/completed-workout grants and does not expose foreign, expired or revoked data. |
| AC-003 | Dedicated bounded projections contain only calendar facts and completed exercise/actual-set facts; no active workout, notes, profile, health, measurement, credential or AI field. |
| AC-004 | Only a live relation authorizes COACH create/revise/revoke and PLAN-01 pending approval; historical author snapshot never authorizes. |
| AC-005 | Either participant revoke instantly blocks reads, mutations and pending apply while accepted recipient records, receipts and history persist. |
| AC-006 | Expiry and accept/revoke/approve/session/delete/first-bind races cannot create duplicate/revived authority, phantom edges or orphan live FKs. |
| B-AC-007 | Backend directory/relation pages bind actor/relation/revision, obey item/byte limits, reject changed snapshots and recheck live identity under locks. Product AC-007/AC-008 remain Android-delegated. |

## Current → target flow, data ownership and frozen contracts

PLAN-01 currently has `TrainingProposalAuthority` plus default deny and immutable author snapshots. Proposal approval remains its sole writer of recipient routine/calendar records. `CoachJournalService` remains self-account data. Actual migration number is `N`, resolved only when the target master chain, including health `011`, is final.

```text
readonly tuple lookup → outer lifecycle guard(s), UUID sorted → locks
coach/invite/COACH proposal: guards → catalog if proposal → recipient head → relation → proposal
  → live coach users row → coach session → snapshot
recipient approve: guards → catalog → recipient head → origin relation → proposal → recipient session
delete: own guard → catalog → own+affected recipient heads sorted → relation rows → health prefix
  → user → session recheck → challenge → detach snapshot → cleanup → user remove
```

- A readonly lookup obtains token/relation/proposal participant tuple before locking. Endpoint takes lifecycle advisory guards **before catalog**: pair paths take coach+recipient guards sorted UUID; account deletion takes only own guard. Every new relation/proposal involving an actor takes its guard, so deletion can enumerate a closed participant set. Guards are never added late inside authority after a head lock.
- COACH proposal order is `guards → catalog → recipient sync_head → relation → proposal → live coach users row → coach session → snapshot`; relation accept/revoke is `guards → recipient head → relation → actor users row → actor session`; projection is `readonly lookup → guards → recipient head(read) → relation → coach users row(read) → coach session`. Recheck tuple, state/grants, revision, user and session after all locks.
- Rewrite actual `AuthService.delete`: readonly guarded enumeration first gathers affected relation IDs and COACH proposal IDs. `proposalCleanup.preflightRecipient` is widened to lock `catalog → all existing affected heads (including own), UUID sorted → relation rows UUID sorted → affected proposal rows UUID sorted`, then `healthCleanup.preflight(advisory + optional state) → users.lock → healthCleanup.revalidate(identity session) → consume challenge → authors.detachLiveAccount → relation/invite/operation cleanup and terminalize already-locked PENDING COACH proposals → health/proposal recipient cleanup → users.remove` (session cascade). Invalid code changes nothing. This preserves the real health prefix and avoids `own head then other head` inversion; barriers prove no new edge/proposal appears after enumeration.
- `bindAuthenticatedCoach` is callable only after locked live coach user and session in that graph; it inserts/locks snapshot FK, rejects detached pointer, and serializes with delete guard/detach. Test first/repeat/detached bind, delete and session barriers.
- Persist immutable historical participant IDs plus nullable detachable live participant FKs on relations/invites; retained REVOKED relation tombstones have `RESTRICT` FKs from proposal and version. `origin_relation_id` is immutable on every COACH `training_proposals` row and every proposal version, new COACH requires non-null matching origin, and migration-only legacy null origin fails closed. A new same-pair relation has a new ID and cannot authorize pending work tied to the old origin.
- `coach_relation_directory_heads` advances for both participants on activation/revoke. Directory cursor binds actor+mode+directory revision; projection cursor binds exact relation ID and locked recipient `sync_heads.revision`. Changed revision is `409 relation_snapshot_changed`; multi-recipient client pages cannot mix snapshots.

The complete draft contract is [coach-relations-contract.json](contracts/coach-relations-contract.json). Strict review accepts it before its byte-identical copy reaches `src/test/resources`.

| Route | Request / result | Rules |
|---|---|---|
| `POST /v1/coach-relations/invitations` | `{operationId}` → `201 {inviteId,token,expiresAtMillis}` | active pair `relation_exists`; exact create replay returns `409 invite_token_not_replayable`, no token and no duplicate. |
| `POST /v1/coach-relations/invitations/accept` | `{operationId,token,calendar,completedWorkouts}` → `200 Relation` | token JSON body only; same consumer/new op/same grants returns existing ACTIVE; changed grants `invite_consent_conflict`; other consumer `invite_used`; revoke never reactivates. |
| `GET /v1/coach-relations/{clients|coaches}` | `?limit=1..50&cursor` → directory page | only relation ID, opaque authorized counterparty ID/state/grants/times; no email/profile. |
| `POST /v1/coach-relations/{relationId}/revoke` | `{operationId}` → sanitized `Relation` | bilateral, terminal replay gives no read capability; foreign/absent/revoked is `404 relation_not_found`. |
| `GET /v1/coach-relations/{relationId}/{calendar|completed-workouts}` | bounded cursor page | active coach and respective grant only; explicit allowlist in fixture. |
| proposal create/revise/revoke below relation | operation ID + expected revision/version + PLAN-01 draft | server derives source/author/origin; pending recipient approval authorizes immutable origin relation only. |

Raw mutators reject empty/non-UTF8/BOM/duplicate-member/trailing/over-512-KiB body before DTO. Ledger has unique `(actorId,operationId)` and stores `action + normalizedRoute + resourceTuple + rawSha256`; changed action/path/resource/body is `409 relation_operation_conflict`, including path substitution. Create tuple is actor only because no generated ID exists before mutation; each other tuple is fixture-defined. Normal endpoints store bytes/result. Accept request has a secret token: its ledger stores only raw SHA and sanitized immutable target/result metadata, never bytes/token. Create similarly stores sanitized invite metadata, so plaintext token is non-replayable. Replays precede liveness only to return no-capability terminal metadata.

Token HMAC is `HMAC-SHA-256(derivedExistingSecret("coach-relations/invite-token/v1"), UTF8(token))`, storing key version/digest and retaining old keys TTL+1 day. Unknown/retired digest is `404 invite_not_found`; malformed token `400 invalid_request`. Cursor HMAC has separate purpose `coach-relations/cursor/v1`; payload `{v,keyVersion,kind,actorId,relationId?,recipientId?,directoryRevision?,recipientSyncRevision?,lastRelationId,lastRecordId,expiresAtMillis}`, TTL 15 minutes; bad/expired/retired are respectively `400 invalid_cursor`, `410 cursor_expired`, `410 cursor_key_retired`.

## Tasks, ownership and waves

| ID | Owner / exact files | Depends | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | Planner: `vibe/contracts/coach-relations-contract.json`; strict reviewer accepts SHA; writer then copies accepted `src/test/resources/coach-relations-contract.json` | PLAN-01 fixture SHA `65254…a998` | Freeze full nested schemas, routes, errors, computed HMAC/cursor vectors, allowlists, ledgers and origin/lock contracts; resolve external PLAN-01 refs from `src/test/resources/`. | `shasum -a 256 vibe/contracts/coach-relations-contract.json src/test/resources/coach-relations-contract.json` | Reviewer accepts fixture SHA; then and only then writer copies identical bytes. | AC-001–006,B-AC-007 |
| T-002 | Sole writer: `src/main/resources/db/changelog/<N>-coach-relations.sql`, `src/main/resources/db/changelog/master.yaml`, `src/main/kotlin/tech/valerochkagym/repository/model/CoachRelationEntities.kt`, `src/main/kotlin/tech/valerochkagym/repository/coachrelation/CoachRelationRepositories.kt`, `src/main/kotlin/tech/valerochkagym/repository/trainingproposal/{TrainingProposalAuthorSnapshots.kt,TrainingProposalAccountCleanup.kt}`, `src/main/kotlin/tech/valerochkagym/service/auth/AuthService.kt` | T-001, stable health chain | Implement migration, tombstone/FK, HMAC/heads/ledgers, exact cleanup/delete/guard graph. | `./gradlew --no-daemon test --tests '*CoachRelationsIntegrationTest'` | Migration/barriers prove no phantom edge, duplicate, regrant/FK orphan or bind/delete race. | AC-001,005–006,B-AC-007 |
| T-003 | Same writer: `src/main/kotlin/tech/valerochkagym/{controller/model/CoachRelationModels.kt,controller/data/CoachRelationsController.kt,service/coachrelation/CoachRelationsService.kt,service/trainingproposal/{TrainingProposalAuthority.kt,DefaultDenyCoachRelationAuthority.kt,TrainingProposalService.kt,TrainingProposalValidator.kt}}`, `src/test/kotlin/tech/valerochkagym/CoachRelationsIntegrationTest.kt` | T-002 | Implement raw routes, projections, outer guards and live origin authority/create/revise/revoke/pending apply. | focused CoachRelations test | Exact replay/errors, grants/bounds/no leak and old-origin fail-close pass. | AC-001–005,B-AC-007 |
| T-004 | Same writer: `src/test/kotlin/tech/valerochkagym/{CoachRelationsIntegrationTest.kt,TrainingProposalIntegrationTest.kt,BackendIntegrationTest.kt}` | T-003 | Barrier/fault tests: consume, revoke/approve, cursor revision/multi-recipient, bind/delete/session and retention. | `./gradlew --no-daemon test --tests '*CoachRelationsIntegrationTest' --tests '*TrainingProposalIntegrationTest' --tests '*BackendIntegrationTest'` | Positive/denial/race evidence for every backend AC; no edited-preview equality claim. | AC-001–006,B-AC-007 |
| T-005T | Independent tester | stable T-002–004 | Run focused AC matrix. | T-004 command | Focused result recorded. | AC-001–006,B-AC-007 |
| T-005V | Independent read-only reviewer | stable T-002–004 | Audit fixture, graph, secret exception, origin fail-close, delete and allowlists; no Gradle. | read-only diff/fixture review | Consolidated no-P0/P1 verdict. | AC-001–006,B-AC-007 |
| T-006 | Original writer, finding files + tracker | T-005T,T-005V | One bounded focused repair; fixture changes require reacceptance. | invalidated focused test | Findings resolved/accepted risk recorded. | affected |
| T-007 | Root only | stable T-005T,T-005V/T-006 | One final server gate. | `./gradlew --no-daemon check bootJar` | PASS or exact blocker recorded. | AC-001–006,B-AC-007 |

One writer owns all mutable files. Waves: T-001 planner draft then strict reviewer acceptance; T-002; T-003 then T-004; parallel focused T-005T and read-only T-005V; T-006 only if needed; root T-007. Relevant gates: fixture SHA, append-only Liquibase, Postgres migration/FK/index/barrier, token/HMAC/cursor vectors, raw-secret non-retention, privacy matrix, focused T/V and root `check bootJar`. Android gates do not apply.

## Risks, preservation and Gate P

Never roll back deployed migration in place: forward migration or route disablement only. Preserve accepted recipient records/receipts/history and detached snapshots. PLAN-01 stored-draft equality remains auto-review blocked; this plan neither removes it nor claims full edited-preview acceptance.

**Gate P re-review ready:** every backend AC (AC-001…AC-006, B-AC-007) maps to task/verification, one writer owns choke points, full draft fixture exists, and immutable origin/FK, fail-close legacy behavior, outer lifecycle guards, actual delete graph, revisions, token/cursor cryptography, secret ledger exception and pending-apply authority are frozen. Product AC-007/AC-008 are explicitly Android-delegated. T-001 acceptance blocks code; recommended first code task then is T-002.
