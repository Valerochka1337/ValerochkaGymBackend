# Stage 23 backend tracker — coach relations

Status: `in_progress` for T-001 draft fixture. No backend source, test resource, migration, Gradle, commit, push, deploy, message or screenshot changed/run.

| Task | Status | Owner | Dependencies | AC | Observable completion |
|---|---|---|---|---|---|
| T-001 | in_progress | planner then strict reviewer then writer | PLAN-01 SHA | AC-001–006,B-AC-007 | Complete draft exists; accepted backend copy/SHA pending. |
| T-002 | pending | sole writer | T-001, health chain | AC-001,005–006,B-AC-007 | Actual-N migration and focused lifecycle test pass. |
| T-003 | pending | sole writer | T-002 | AC-001–005,B-AC-007 | Routes/projections/live authority test passes. |
| T-004 | pending | sole writer | T-003 | AC-001–006,B-AC-007 | Barriers, cursor and PLAN-01 regression pass. |
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
- Fixture is draft only and not copied to test resources. PLAN-01 edited-preview equality remains auto-review blocked and untouched.

## Command results and residual risks

No Gradle command ran because this is planning/fixture work. The remaining blocker is strict T-001 fixture review; actual migration number remains unknown until the final health chain is observed. Parent should rerun `git diff --check` after artifact inspection.

## Final Gate P acceptance

Independent Sol/high narrow recheck PASS: all seven P1 repairs closed. Root resolved
two P2 wording defects by explicit wireKeys/semantic aliases matching verified
cursor vector and separating 691200-second retired-key lifetime from database
tombstone retention. No production secrets or code changed. Accepted fixture SHA-256:
`a4fb21eae8dcc557e3ae9b53301324d5d47edb73d6c231f556509060f1c99da1`. Writer copies these exact bytes before T-002.
