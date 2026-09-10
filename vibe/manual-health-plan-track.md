# Manual health backend tracker — healthLedgerV1

Status: done_local. T-001–T-007 PASS; no publication or production deployment claimed.

## Frozen inputs

| Item | Value |
|---|---|
| Android fixture source | Android commit `968a0f2`, `vibe/contracts/manual-health-contract.json` |
| Fixture SHA-256 | `33d74a1de76f9e444e02972f463a915b6e29c338c4c76bbac3aea4ce2305f5c0` |
| Backend fixture copy | Copied byte-identically on feat/manual-health-ledger, base 7f27801; SHA verified. |
| Migration number | 011-manual-health.sql; actual master ends at 010-training-proposals.sql; no published changeset edited. |
| Capability | `health-ledger-v1` in `X-Gym-Capabilities`; all dedicated health/disclosure routes deny without it. |
| Default AI disclosure | Absent projection `(revision=0, noticeVersion=0, enabled=false, recordedAtEpochMs=0)`; InBody fails closed. |

## Task status

| Task | Status | Owner | Completion evidence |
|---|---|---|---|
| T-001 fixture and migration freeze | done | Sole backend writer | Byte-identical fixture copy, SHA/cmp result, and N+1 recorded before code. |
| T-002 schema/repositories | implemented | Sole backend writer | Append-only migration and repository constraint integration proof. |
| T-003 ledger/raw/cursor API | implemented | Sole backend writer | Strict raw/idempotency/head/as-of integration matrix passes. |
| T-004 disclosure/InBody/delete integration | implemented | Sole backend writer | Pre/post revoke gate and owner-only delete barrier pass. |
| T-005 tests | done | Sole backend writer | Focused formatter/Testcontainers command passes. |
| T-007 root final check/bootJar | done | Root |120 tests, 0 failures/errors/skips; check bootJar PASS in1m9s, log /private/tmp/yarumo-health-final-check.log. |
| T-006 review | done | Independent backend reviewer | Narrow GateT and Sol/high GateV PASS after all four fixes. |

## Traceability

| AC | Tasks | Required observable tests |
|---|---|---|
| AC-HB001 | T-001,T-003,T-005 | capability omitted/downgraded returns 426 before decode/row/token change. |
| AC-HB002 | T-002,T-003,T-005 | raw replay, changed-byte conflict, strict JSON/body limits, result-byte replay. |
| AC-HB003 | T-002,T-003,T-005 | equal/collision, parent/cycle/report liveness, APPLIED/STALE/ALREADY_CURRENT and shared revision counters. |
| AC-HB004 | T-002,T-003,T-005 | all-page combined traversal, as-of H, head-only race, foreign/malformed/expired token. |
| AC-HB005 | T-002,T-004,T-005 | zero receipt, disclosure replay/CAS, pre-image deny and provider-return revoke. |
| AC-HB006 | T-002,T-004,T-005 | PostgreSQL mutation/delete/revoke barriers, invalid-code rollback, other-owner preservation. |
| AC-HB007 | T-001–T-006 | fixture vectors, payload/schema checks, no health measurement projection, privacy counts. |

## Planning-pass result

Only `vibe/manual-health-plan.md` and this tracker were created. No fixture copy, production source, migration, test source, Git mutation, Gradle command, provider call, or deployment occurred.

## Handoff repair — 10.09.2026

T-004 now explicitly owns BearerFilter/Security before-body admission, ASYNC reauthentication, zero-read/zero-provider proofs and pre/post-provider checks without HTTP-held DB locks. Dedicated capability precedes raw reading. Page-token TTL24h and purpose separation, committed-cursor noTTL/history/key-rotation410, configurable 200MiB/100000-version atomic storage quotas with free replay/tiny-quota tests, and root-only final check/bootJar after independent review are frozen in the plan. No health code or fixture copy has begun; migration remains unassigned until PLAN01 is saved. These documentation repairs await independent review.

Independent Sol/high narrow repair review 10.09.2026: PASS, no P0/P1/P2. The before-body/ASYNC, zero-read/provider, cursor/quota and root-gate requirements above were verified in the final plan. T-001 still requires the actual next migration and byte-identical fixture before implementation.

## Implementation decisions and evidence — 10.09.2026

- Actual base is 7f27801 on feat/manual-health-ledger. Master 001–010 has 11 changesets; new append-only 011-manual-health adds the twelfth. Backup/schema count assertions move 11→12 and exact source/restored history parity remains asserted.
- T-001: canonical test fixture copied byte-for-byte, cmp PASS, SHA `33d74a1de76f9e444e02972f463a915b6e29c338c4c76bbac3aea4ce2305f5c0`.
- JDBC repositories own the ledger SQL rather than duplicate JPA entities. Strict JsonNode validators and a deterministic writer implement exact wire field sets/property order instead of introducing DTO copies. Existing project JdbcTemplate/TransactionTemplate and Crypto secret management are reused; no dependency added.
- All writes serialize on the single owner-state row. New versions and matching head changes are fully classified/validated and their quota cost computed before allocation/persistence. Owner serialization means child row acquisition cannot interleave between same-owner writers; no health writer acquires a generic catalog/head lock. Deletion retains catalog→existing sync head→existing health state→user/session; it revalidates session before consuming the code. No owner-state creation on invalid delete, read, or AI admission.
- Quota counts logical UTF-8 bytes of immutable version bodies, immutable event bodies (including head history projection), raw operation bytes and stored result bytes, including disclosure operations. SQL row/index overhead is excluded. Limits default to 200MiB/100000 versions, configurable by gym.health.max-bytes/max-versions. Exact replay occurs before quota accounting.
- Filter REQUEST and ASYNC admission requires live authentication and current InBody receipt before chain/body decoding. Dedicated health capabilities precede any raw reader. Service checks before provider and after provider each finish their transaction before HTTP/response validation; ASYNC filter repeats authorization at response dispatch.
- Filter counting-stream assertions reside in HealthLedgerIntegrationTest to exercise the actual autowired auth/disclosure services; there is no separate HealthAdmissionFilterTest context. Cursor/validator unit tests are in service/health. Existing AiIntegrationTest covers provider-return revoke and unchanged exercise action behavior.

First integrated focused gate PASS:75 tests (HealthLedgerIntegrationTest12, HealthLedgerValidatorTest3, HealthCursorCodecTest1, AiIntegrationTest12, BackendIntegrationTest47), 53s. It ran spotlessApply and selected test classes, not check/bootJar. Subsequent final-session/delete-barrier changes are undergoing their affected targeted gate; the first result does not substitute for that final gate.

PLAN01 stored-draft equality and the rejected edited-preview patch remain untouched. No commit, push, deployment, provider-live call or Android edit belongs to this slice.


Final production-change gate PASS:88 tests (HealthLedgerIntegrationTest13, HealthLedgerValidatorTest3, HealthCursorCodecTest1, BackendIntegrationTest47, TrainingProposalIntegrationTest24),57s. This validates final deletion session recheck, confirmed deletion/mutation serialization, persisted fingerprint equality and existing proposal cleanup. Additional semantic tests only are being finalized in a focused health rerun; no further production change.

## AC → concrete proof

| AC | Implemented boundary | Observable tests |
|---|---|---|
| HB001 | BearerFilter/Security before chain, capability response header | `invalid raw capability and schema leave every health table empty`; `filter rejects absent stale disabled consent and capability with zero body reads including async` |
| HB002 | HealthRawBodyReader, HealthLedgerValidator, raw byte/result operation ledgers | `exact raw replay preserves result and changed bytes conflict`; canonical SHA vectors; schema negatives/missing fields; quota replay at capacity |
| HB003 | owner-state transaction, immutable versions/event index/history/head CAS | `two corrections keep both versions and one winner`; `collision parent order and missing head base reject atomically`; stored-observation/tombstone retry; cross-owner/cyclic/substituted-head rejection; matching-head liveness vs stale no-op |
| HB004 | HealthCursorCodec; immutable SQL as-ofH combined event selection | `frozen snapshot and changes retain head only history in revision order`; page24h vs committed noTTL/purpose/owner/rotated-key unit test |
| HB005 | independent disclosure raw ledger/CAS; filter and short service pre/post transactions | `disclosure exact replay and CAS are independent of ledger revisions`; counting REQUEST/ASYNC filter test; existing AiIntegrationTest InBody callback revokes during provider and proves zero subsequent calls |
| HB006 | AuthService catalog/head/health prefix, final session lock/recheck, explicit owner cleanup | invalid-code preservation, confirmed-delete self-referenced history/other-owner measurement preservation, session revoke while owner lock held, confirmed-delete/mutation barrier; existing Backend/TrainingProposal integration suites |
| HB007 | exact field validation/canonical serialization, owner SQL/FKs, dedicated routes | fixture invalid vectors, UUID/type/date/decimal checks, cross-owner reference/token tests, measurement exclusion, no durable AI request/result table |

Storage quota proofs: byte quota1 rejects with no owner-state row; count quota1 rejects new version while exact replay succeeds; concurrent count quota2 admits exactly one addition. `git diff --check` and fixture cmp/SHA are final static gates. T-006 independent review and T-007 root check/bootJar remain separate acceptance gates.

Final health-only rerun PASS: 20 tests, 0 failures,21s (HealthLedgerIntegrationTest16, HealthLedgerValidatorTest3, HealthCursorCodecTest1). No production changes after the88-test gate. Fixture cmp/SHA and git diff --check PASS. Gate I is stable and ready for independent T/V; no running backend Gradle process remains.

Independent Gate T audit: PASS, no P0/P1/P2 or uncovered relevant AC. Existing targeted evidence was not repeated without a concrete gap. Root separately confirmed fixture cmp/SHA against the absolute Android source path. Strict Gate V remains in progress; final check/bootJar has not started.

Strict Gate V result: **not accepted**, 2 P1 + 2 P2. Non-null original-text fields accept empty strings against NullableText1000; first absent owner-state creation can race confirmed deletion into FK/500; malformed/missing paging params bypass ApiError; paging retains owner/session locks through serialization. One consolidated writer fix batch is in progress. The earlier Gate T audit missed the empty-string and absent-state race vectors; final acceptance requires their actual regression coverage and narrow re-review. Root full check/bootJar remains withheld.


## Consolidated Gate V fix batch — 10.09.2026

The root Gate V failure block above is preserved; its findings are not accepted closed until independent re-review.

- P1a: NullableText1000 unit/method/specimen/source now explicitly passes min1. HTTP tests exercise 0/1/1000/1001 for each field and compare immutable versions/events/operation counts after rejection.
- P1b: Every owner-state lock first takes a transaction-scoped PostgreSQL advisory mutex `(18492417,hashtext(ownerUUID))`. The mutex exists before the first persistent row and is reused by confirmed-delete preflight before users.lock. Hash collisions only serialize independent owners; the UUID continues to scope every query. No row is materialized by preflight/read/admission. Established order becomes catalog→existing sync head→health advisory mutex→existing health state→user/session for deletion; health mutations take only health mutex/state→session. Absent-state first ledger and disclosure writes are tested waiting on the real PostgreSQL advisory lock before confirmed deletion commits, then return unauthorized with zero state/operations.
- P2a: Health query parameters bind as optional/string values and are explicitly parsed through ApiException invalid_request. Missing after and malformed/overflow limit return exactly code/message, with no Spring default envelope.
- P2b: Page capture holds owner mutex/state and session only long enough to freeze H and authenticate. One immutable-event SQL statement runs outside those locks, using its single MVCC statement snapshot. Serialization also runs outside locks; a short final session/revocation check discards bytes after concurrent account deletion/revoke. Test pauses after actual event rows have been read, proves owner mutation/delete/logout finish before read resumes, verifies frozen H for writes and unauthorized (no data result) for delete/revoke.

Focused fix-batch gate PASS:24 tests (HealthLedgerIntegrationTest20, validator3, cursor1),30s. Two concurrency proofs were then strengthened to assert an actual pg_locks waiter and pause after nonempty event results; the smallest rerun of those two tests PASS (2 tests,0 failures,24s). No full gate, commit or push; PLAN01 equality unchanged.

Fix batch complete: git diff --check PASS. Production remained unchanged after the24-test gate; only deterministic barrier assertions were strengthened and rerun. Writer stopped, no active Gradle. Gate V findings await independent re-review; root full remains withheld.
