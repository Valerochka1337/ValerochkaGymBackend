# PLAN-01 backend tracker — training proposals

Status: safe implementation subset passes final check/bootJar; full PLAN01 acceptance remains blocked by edited-draft approval decision.

## Task status

| Task | Status | Owner | Dependencies | AC | Observable completion |
|---|---|---|---|---|---|
| T-001 | done | Contract acceptance owner | fixture writer accepted SHA | AC-B001–B007 | Android ca41f1f; SHA 65254ebf9aaa4062ebf8ef71df99c76876685ce10ec0bd2fa8645fced56ea998; root-copied backend test resource identical. |
| T-002 | partial; edited-draft approval blocked | Sole backend writer | T-001 | AC-B001–B007 | Migration 010, immutable proposal/version/receipt/operation state, immutable COACH audit snapshots with one-way live-account detach, internal AI creator, recipient routes, recipient-scoped opaque cursor, strict raw approval boundary, default-deny coach seam, and atomic sync records pass focused PostgreSQL integration coverage. |
| T-003 | done | Sole backend writer | T-002 | AC-B002–B006 | Barrier double-approve and approve/delete lock-order cases, exact replay/changed bytes/different operation, terminal and context guards, authority deny/error, database-fault rollback, account-delete snapshot/FK/no-reattach, and exact gym/type bounds pass with record/head/receipt/ledger assertions. |
| T-004 | pending | Independent backend tester/reviewer | T-002,T-003 | AC-B001–B007 | `./gradlew --no-daemon check bootJar` passes or one consolidated finding packet exists. |
| T-005 | pending | Original backend writer | T-004 findings | affected | Invalidated test and, if code changed, final server gate pass. |

## AC → task → test traceability

| AC | Tasks | Tests / checks |
|---|---|---|
| AC-B001 | T-001,T-002,T-004 | fixture SHA/parity; create/version/expiry/status snapshot integration cases. |
| AC-B002 | T-002–T-004 | recipient/author/unrelated/guest matrix; AI spoof; default-deny/missing/throw authority; locked revoked-session cases. |
| AC-B003 | T-002–T-004 | malformed raw JSON, bounds, canonical UUID/type, archive/equipment/context, expiry and active-workout cases. |
| AC-B004 | T-001–T-004 | raw-byte SHA vectors; same operation exact replay/changed bytes; different operation after approval; response-loss recovery. |
| AC-B005 | T-002–T-004 | catalog/head/relation/proposal/session lock order, barrier/deadlock, rollback and atomic record/head/receipt/ledger queries. |
| AC-B006 | T-002–T-004 | recipient-only reject, author-only revoke, terminal idempotency and history/unrelated-record preservation. |
| AC-B007 | T-002,T-004 | bounded list/detail/result pagination and hidden-proposal/privacy projection tests. |

## Deviations

The accepted author-history remediation rewrites unpublished `010-training-proposals.sql`: COACH `author_id` now references an immutable `training_proposal_authors` audit row with `CHECK (live_account_id IS NULL OR live_account_id=historical_account_id)`. It keeps receipt/version foreign keys `RESTRICT`, leaves another recipient's approved result intact, and detaches only the deleting account's live pointer after a valid delete code; `ON CONFLICT DO NOTHING` prevents reattachment. Confirmed delete preflights `catalog → existing recipient head` before taking the user lock, so it neither inverses approval's prefix nor materializes a head on an invalid code. Proposal cursors encode the last emitted UUID and resolve it only under the current recipient. This plan uses the actual backend baseline `03a0c1ddc0deec53f30e8138b58eb85acc0edcfa`, whose changelog currently ends at `009-basic-profile.sql`; the planned next append-only migration is `010-training-proposals.sql`.

## Findings

- `SyncService` establishes the existing catalog → owner-head serialization boundary and `RecordValidator` already validates routine/calendar record shapes and references.
- `CoachJournalService` is immutable self-account journal data and cannot authorize a coach or store proposals.
- Session authentication is normally stateless at the filter; proposal mutations must additionally lock and revalidate `SessionEntity` inside their transaction.

## Command results

`DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon spotlessApply test --tests '*TrainingProposalIntegrationTest'` — PASS (24 tests, migration 010 applied in PostgreSQL Testcontainers). Earlier focused compile and formatter passes were subsumed by this command. Historical root full `check bootJar` FAILED: 96 tests, one backup Liquibase count failure (expected 10, actual 11); bootJar completed but the gate did not pass. See repair evidence below.

## Residual risks

- Root closed the fixture gate after independent strict review; T-002 may start.
- Stage 23 relationship persistence is intentionally absent; `DefaultDenyCoachRelationAuthority` must keep COACH disabled until its replacement is reviewed.
- The server may approve while Android has not yet projected its accepted result; immutable receipt and operation replay preserve recovery, while Android owns local exactly-once materialization.

## Handoff repair — 10.09.2026

The backup assertion now expects 11 changesets: master includes 001–010 and 001 contains both auth and sync changesets. The test also compares the complete source databasechangelog rows, captured before pg_dump, with restored history. No migration was modified by this repair.

The initial focused backup regression with corrected count passed. Final targeted parity regression: PASS, 1 test, 0 failures (19s): `DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon test --tests '*BackendIntegrationTest.backup restores account records and Liquibase history into a separate database' --console=plain`. Source/restored history match and restored count is 11. `git diff --check` PASS. Full check/bootJar deliberately deferred until independent read-only review is accepted; root owns that final run.

The existing stored-draft equality remains unchanged. `/private/tmp/yarumo-proposal-edited-approval-proposed.patch` was not applied: automatic approval review previously rejected its removal; explicit user decision remains necessary. This means locally edited preview approval is not implemented and PLAN01 is not fully accepted. Earlier 24-test focused success and safe-subset independent review do not close that gap. No commit/push/deploy occurred.

Independent Sol/high narrow backup repair review 10.09.2026: PASS, no P0/P1/P2; both count assertions match the 11 master changesets and complete source/restored history is compared. Root final `check bootJar` started after this acceptance; log `/private/tmp/yarumo-training-proposals-takeover-check.log`, result pending.

Root final gate result: **PASS**, 96 tests, 0 failures/errors/skipped, `check bootJar` successful in 47s. This closes the historical backup failure only; edited-preview AC remains blocked. Safe subset is saved separately for preservation and future continuation, not declared complete or released.
