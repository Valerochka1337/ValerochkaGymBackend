# Calendar AI backend — Stage 21 tracker

Plan: [`calendar-ai-plan.md`](calendar-ai-plan.md). Executable contract: [`contracts/calendar-ai-contract.json`](contracts/calendar-ai-contract.json), schemaVersion 2. This repair replaces the rejected planning assumptions only; no backend code or PLAN-01 editable-preview patch is changed.

## Status

| Task | Status | Owner | Depends on | AC | Evidence / exit |
|---|---|---|---|---|---|
| T-001 | pending | fixture/contract owner | accepted PLAN-01 fixture | B-AC-001,003,007,008 | Pin and byte-check `training-proposals-contract.json`; JSON schema/reference/vector validation V-001…V-011. |
| T-002 | pending | sole backend writer | T-001 | B-AC-001,002,007,008 | Migration 013, attempt ledger/cleanup, raw route, shared ticket, frozen status action and provider naming; focused action/provider/AI tests. |
| T-003 | pending | sole backend writer | T-002 | B-AC-001–007 | Indexed bounded reader/context/projection/final creator transaction; EXPLAIN and limit tests. |
| T-004 | pending | sole backend writer | T-002,T-003 | B-AC-001–008 | All deterministic V-001…V-011 tests, barriers and zero/one proposal counts. |
| T-005 | pending | independent focused tester | T-004 | B-AC-001–008 | Focused test execution and vector-to-result audit; no production edit. |
| T-006 | pending | independent read-only Sol reviewer | T-004 | B-AC-001–008 | Strict narrow review of schema/ledger/locks/cancellation/SQL/privacy; distinct from T-005. |
| T-007 | pending | original backend writer | T-005,T-006 findings | affected | One consolidated repair with affected targeted evidence. |
| T-008 | pending | root only | accepted T-007 | relevant | One backend `check bootJar`; Android AC-009/010 remain delegated. |

## AC traceability

| AC | Implementation | Evidence |
|---|---|---|
| B-AC-001 | T-002–T-004 | V-001 strict input; V-006 bounds; V-008 readiness/gym intersection. |
| B-AC-002 | T-002–T-004 | V-005 final ticket winners; V-011 deletion/outer-lock order. |
| B-AC-003 | T-003,T-004 | V-006 bounded SQL/EXPLAIN; V-007 privacy/temporal marker. |
| B-AC-004 | T-003,T-004 | V-008 deterministic context ordering only. |
| B-AC-005 | T-003,T-004 | V-009 explicit actual-null, legacy fallback and cross-exercise exclusion. |
| B-AC-006 | T-003,T-004 | V-007 optional/no-health/no-notes behavior. |
| B-AC-007 | T-002–T-004 | V-001, V-006, V-009, V-010 strict rejection. |
| B-AC-008 | T-002–T-004 | V-002 replay, V-003 conflict/concurrency, V-004 crash, V-005 callback/commit, V-011 deletion. |

## Frozen repair decisions

- `master.yaml` currently ends at `012-coach-relations.sql`; Calendar AI appends `013-calendar-ai-attempts.sql`. The ledger binds `(ownerId, requestId)` to SHA-256 of exact accepted raw request bytes, not the raw bytes. It never stores prompt/provider input/output/error. `PROCESSING`, `COMMITTING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `INTERRUPTED`, 45-second deadline and 60-second lease have the contract semantics.
- `relations.guards(owner)` is outermost at reserve/capture/final before catalog/head/session. Provider I/O occurs after capture transaction commits. Final ticket ownership immediately before proposal insert makes proposal+success receipt one transaction; cancellation before that leaves zero proposal.
- Bounded history materializes 65 parent workouts then rejects over 64 and expands only that bounded set; fact/candidate/context caps, index/EXPLAIN and data-quality behavior are contract-mandatory. Do not silently loosen existing source body limits.
- Goal groups order provider context only. They do not force response prefix/seed/type or make a physiological claim. Selected gyms are the live owner-gym/catalog intersection.
- Provider output has no `weightKg`, `speedKmh`, `inclinePct`; server projects null speed/incline and same-exercise actual-member (including null) then legacy weight. Timed/cardio known duration plus rests fits the request; strength fit is unknown.
- `/v1/ai/status` adds frozen `CALENDAR_DRAFT`; calendar never needs health disclosure and never imports health/InBody. PLAN-01 edited-preview equality is blocked and untouched.

## Validation record

Planning repair only: no Gradle. Before write, read Android `calendar-ai-brief.md` including its later goal-ranking clarification, current `master.yaml`, `AiController`, `AiActionService`, `AiProvider`, `HttpOpenAiChatCompletionsProvider`, `TrainingProposalAiCreator`, `TrainingProposalService`, and PLAN-01 fixture. The fixture digest is `65254ebf9aaa4062ebf8ef71df99c76876685ce10ec0bd2fa8645fced56ea998`.

Required current planning checks after write: parse contract JSON, resolve every internal `#/$defs/` reference, assert required strict schemas and all V-001…V-011, compare pinned PLAN-01 SHA, and cross-reference every task/AC/vector. Implementation gates are T-004/T-005 only.

## Risks / handoff

The actual migration, constraint/index compatibility and SQL `EXPLAIN` claim remain implementation-time checks; a source body cap incompatible with the finite context contract is a blocker, not permission to enlarge a bound. PLAN-01 AC-009 and Android AC-010 are not claimed. Route/provider schema action changes must retain exercise/InBody behavior under their existing tests.


## Root bounded repair checkpoint

Repeat Gate P REJECT: seven P1 remain in provider envelope, source-compatible schemas, standalone
provider schema, concrete vectors/calculations, end-to-end SQL bounds, immutable receipt and
provider privacy projection. Original reviewer supplied one exact batch. Contract writer repairs
only the fixture; root records the concrete execution/ownership changes in plan's final section.
No implementation, Gradle or publication occurs before acceptance. Previous fixture SHA is
superseded by the pending repair; T-001 must record the new actual SHA after independent recheck.


Root fixture consistency correction: `84865187f97dec02a4f664d377d03a30c80bba8207c8a1c885ae5669765eb23e`.
Corrected vector clocks relative to 45s deadline/60s lease, exact request hashes and repeated
response projections, coverage arithmetic, duration error status and deleted-owner ledger cleanup.
Temporary validator `PYTHONPATH=/private/tmp/yarumo-contract-validator python3
/private/tmp/yarumo-validate-calendar-contract.py` passed 94 JSON Schema and semantic assertions.
`jsonschema==4.25.1` is installed only under /private/tmp; project dependencies did not change.
Independent reviewer rechecks the seven surviving findings. Gate P is not yet declared accepted.


## Final Gate P acceptance

Independent Sol/high narrow recheck PASS for fixture SHA
`42714ea6086c8d7349543cfdb3d11cfac86d04fe67b15ec743ff388f4e31b18e`.
Final root validator:102 schema/semantic assertions PASS. Remaining2P1/1P2 fixed:
exact Candidate maxima and integer coverage, runtime schema excludes custom annotations,
sourceRows per-row accounting and exact over-limit distributions. No open planning findings.
T-001 runtime fixture copy and all T-002–T-008 runtime gates remain pending.
Backend feature branch `feat/calendar-ai` starts at accepted coach650baf2.
No edited-preview equality change, live provider call, GitHub write or deployment authorized now.
