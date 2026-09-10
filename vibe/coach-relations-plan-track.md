# Stage 23 backend tracker — coach relations

Status: **Gate I passed** for T-001 copy / T-002–004 on 2026-09-10. Implementation is frozen for independent T-005T/T-005V. No commit, push, deploy, screenshot or full server gate was performed by the writer.

| Task | Status | Owner | Dependencies | AC | Observable completion |
|---|---|---|---|---|---|
| T-001 | completed | planner then strict reviewer then writer | PLAN-01 SHA | AC-001–006,B-AC-007 | Accepted source-compatible fixture copied byte-identically; SHA f5960d…1460. |
| T-002 | completed | sole writer | T-001, health chain | AC-001,005–006,B-AC-007 | Actual-N migration and focused lifecycle test pass. |
| T-003 | completed | sole writer | T-002 | AC-001–005,B-AC-007 | Routes/projections/live authority test passes. |
| T-004 | completed | sole writer | T-003 | AC-001–006,B-AC-007 | Barriers, cursor and PLAN-01 regression pass. |
| T-005T | pending | independent tester | stable T-002–004 | AC-001–006,B-AC-007 | Focused result. |
| T-005V | pending | read-only reviewer | stable T-002–004 | AC-001–006,B-AC-007 | No-P0/P1 consolidated verdict. |
| T-006 | pending | original writer | T-005T,T-005V findings | affected | Invalidated focused test passes. |
| T-007 | pending | root only | stable T-005T,T-005V/T-006 | AC-001–006,B-AC-007 | `./gradlew --no-daemon check bootJar` recorded. |

## AC → task → test traceability

| AC | Tasks | Verification |
|---|---|---|
| AC-001 | T-001–004 | token/HMAC vectors; self/expiry/unknown/other consumer; same-grants replay, changed grants, pair-exists and consume barrier. |
| AC-002 | T-001,003,004 | independent grants; coach/client/guest/foreign/revoked matrix and no enumeration. |
| AC-003 | T-001,003,005 | allowlist snapshots; forbidden fields, 50/1-MiB and cursor bounds. |
| AC-004 | T-001,003,004 | origin FK/version match, legacy null fail-close, old relation/new pair refusal and pending apply. |
| AC-005 | T-002–005 | bilateral revoke/approve race, no-capability replay, history preservation. |
| AC-006 | T-002,004,005 | outer guards, sorted heads, first/repeat/detached bind/delete/session barriers and FK/count checks. |
| B-AC-007 | T-001–T-007 | both-side directory revisions, multi-recipient snapshot, signed cursor/TTL/rotation and root gate. Product AC-007/AC-008 are Android-delegated. |

## Deviations and findings

- Immutable `origin_relation_id` is required on every COACH proposal and version; retained relation tombstone prevents a new pair relation from authorizing old pending work. Legacy null origin fails closed.
- Guards are taken before catalog from readonly tuple lookup. Delete holds its own guard; all new edges involving that actor take it, eliminating phantom relation/proposal creation during deletion enumeration.
- Delete retains the actual health prefix and obtains all affected recipient heads in UUID order before relation/user/session/snapshot locks.
- Token is JSON-body-only, canonical 43-char base64url and purpose/version HMAC-digested. Accept stores hash plus sanitized result metadata, never token/raw bytes.
- Accepted fixture is copied byte-identically to test resources. PLAN-01 edited-preview equality remains auto-review blocked and untouched.
- Legacy operation-ID-free proposal revoke remains 403; new COACH revoke uses the relation-scoped operation ledger.
- Crypto codec is isolated in service/coachrelation/CoachRelationCrypto.kt; versioned retained key configuration is documented in docs/api.md.
- Directory revision reads retain a PostgreSQL FOR SHARE lock, including against counterpart account deletion that holds only its own lifecycle guard.

## Command results and residual risks

Migration is append-only `012-coach-relations.sql` after verified health 011. It adds two changesets (databasechangelog count 14), relation/invite/ledger/directory persistence, detachable live FKs and proposal/version immutable-origin triggers.

Writer validation:
- `compileKotlin`: PASS.
- `spotlessApply`: PASS; existing formatting only.
- Final `test --tests '*CoachRelationsIntegrationTest' --tests '*TrainingProposalIntegrationTest' --tests '*BackendIntegrationTest'`: PASS, 90 tests, 0 failures/errors, 41 seconds.
- `git diff --check`: PASS; final fixture parity SHA `f5960d8a8fd269518aa6347b18607a778e4eff7f64aa5ecd9a2dc529aa501460`.

PostgreSQL evidence includes concurrent invite consumers; deletion/first author bind waits; revoke/pending approval waits; session/read waits; directory revision/counterpart-delete waits; actual-origin approval/revise/revoke; legacy-null denial; detached live-FK retention; 200 exercises/1000 sets/empty arrays/zero metrics/explicit actual null; singleton response >1 MiB; cursor revision, HMAC and key retirement. Barriers assert a real ungranted pg_locks row before release, not elapsed-time assumptions.

Remaining gates: independent T-005T/T-005V and root-only `check bootJar`. No full edited-preview acceptance claim: stored-draft equality is unchanged. Rotation requires retaining old pepper material until eight days after its last issuance, as documented; no production secrets were read.

## Final Gate P acceptance

Independent Sol/high narrow recheck PASS: all seven P1 repairs closed. Root resolved
two P2 wording defects by explicit wireKeys/semantic aliases matching verified
cursor vector and separating 691200-second retired-key lifetime from database
tombstone retention. No production secrets or code changed. Accepted fixture SHA-256:
`a4fb21eae8dcc557e3ae9b53301324d5d47edb73d6c231f556509060f1c99da1`. Writer copies these exact bytes before T-002.

## Source-compatibility amendment before projection implementation

Writer discovered draft caps 30 exercises/20 sets and min1 excluded existing valid
records. Root independently read RecordValidator array helper178–181, metrics229–253,
routine444–451 and workout480–486: arrays may be empty, maxima200/1000, metrics may
be zero. Canonical schemas now preserve these bounds; actual* member presence
(including explicit null) wins over the corresponding legacy metric, with no target
fallback. A single item over1MiB fails413 rather than truncating. New accepted SHA:
`f5960d8a8fd269518aa6347b18607a778e4eff7f64aa5ecd9a2dc529aa501460`.
Additional narrow reviewer dispatch was unavailable (`agent thread limit reached`);
root performed this source check, and independent implementation T/V must verify
these boundary cases before acceptance. Previous Gate P remains evidence for the
unchanged relation/authority/ledger/locking contract.

## Final local backend acceptance

Independent Gate T PASS and separate Sol/high Gate V PASS. Reviewer confirmed
fixture parity, actual lock/FK order, immutable origin, replay binding, grant/privacy
projection and cursor/key boundaries. Root final `check bootJar` PASS:
139 tests, 0 failures/errors/skips, 50 seconds; log
`/private/tmp/yarumo-coach-final-check.log`. Server slice done_local; Android stage23
and PLAN01 edited-preview acceptance remain separate and incomplete. No deployment
or publication claimed. User explicitly requested continuing locally pending GitHub
write access (10.09.2026).
