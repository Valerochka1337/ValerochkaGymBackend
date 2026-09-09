# CAL-01 backend contract — T-003

2026-09-10; owner backend_calendar_implement. Base `dba59ae`, local branch
`feat/calendar-plan-contract`; root owns commit/publication/deployment.

## Contract and implementation

- Accepted optional header: `X-Gym-Capabilities: calendar-plans`, comma-separated requested
  capabilities intersect with the supported singleton; response advertises only accepted values.
- Snapshot, changes (before pagination), records list and record lookup hide all three calendar
  kinds without acceptance. Calendar POST changes, including tombstones and ledger retries,
  require capability before mutation. Existing v2/v3 and Live Coach guards remain unchanged.
- Additive Liquibase `007-calendar-plans.sql` expands the existing records CHECK, no data rewrite.
- Plan/rule/exception payloads reject unknown fields; canonical reference UUIDs, real ZoneIds,
  strict HH:mm/local-date syntax and local-zone inclusive 1970–2100 bounds are validated.
- Post-batch references enforce same-owner live routine/rule graphs, deterministic UTF-8 exception
  UUID and original date/day/time/zone identity, source-key uniqueness and moved/cancelled shape.
  Rule schedule changes require a fresh UUID (including isoDay); routine-only edits may retain it.
  Child and parent tombstones may be sent in any order because the transaction validates the
  final graph. No Google metadata, scheduling job, AI or new account-level version gate is added.
- Fixture `src/test/resources/cal01-sync-contract.json` is root-owned and untouched.
  SHA-256: `d841e2a65037ef94993575ac2dea4172ffaa272cdde63baa2a6867ba0ed29911`.

## Verification

Gate I PASS (2026-09-10). Final command:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew --no-daemon spotlessApply test --tests '*BackendIntegrationTest*' bootJar spotlessCheck
```

- Final exit 0, BUILD SUCCESSFUL in 38s; 39 tests, 0 failures, 0 errors, 0 skipped.
- Four new calendar tests cover real HTTP capability/owner/ledger/legacy visibility,
  original DST keys, atomic references/deletions, schema/zone/date boundaries and frozen fixture.
- Initial run was 38 tests / 2 failures solely from prior expected Liquibase count 7;
  additive 007 raises count to 8. Backup-restore and history expectations updated; both now pass.
- PostgreSQL Testcontainers applied all 8 changesets and backup/restore test passed.
- `bootJar`, `spotlessCheck`, `git diff --check` pass. Existing compiler warnings are unchanged.
- `docs/openapi.json` refreshed from the final real HTTP-generated report with configured server
  URLs preserved. All five data operations expose optional capability input.
- No Git mutation, publication, production compose, deployment, or real .env access performed.

Stable handoff: implementation writes stop here for independent review.

Boundary note: backend stores wall-time recurrence and validates original DST gap/overlap keys;
actual occurrence instant resolution remains Android-owned. No server-side recurrence expansion.
Production compose/deployment remain root-owned. Root expanded this slice to include protocol
documentation and the runtime-generated OpenAPI (existing production/local server URLs retained).
The generated spec also catches up previously shipped Live Coach routes missing from the old file.


## Gate V bounded fixes — final Gate I PASS

2026-09-10, independent review packet addressed; writes stopped again for review.

- P1: live `calendar_rule` writes cannot resurrect an existing tombstone. HTTP tests cover
  identical payload and changed day/time/zone/start, preserved tombstone/revision and valid exact
  delete-operation retry; a fresh UUID remains accepted.
- P2: `Change` has an explicit wire creator retaining raw ID spelling long enough to enforce
  canonical lowercase aggregate UUIDs only for the three calendar kinds. Legacy UUID handling
  remains unchanged; real HTTP regression accepts uppercase legacy routine and rejects uppercase
  calendar plan/rule/exception IDs before any revision mutation.
- P2: all five data operations carry OpenAPI accepted-capability response-header annotations.
  Runtime OpenAPI test checks each header schema and preserves inferred response body content;
  checked-in OpenAPI regenerated from that successful HTTP response.
- Root's expired-access test now uses absolute UTC year-2000 TIMESTAMPTZ to avoid DB/JVM clock
  differences. Preserved; targeted test passes.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew --no-daemon spotlessApply test \
  --tests '*BackendIntegrationTest.calendar*' \
  --tests '*BackendIntegrationTest.expired access*' \
  --tests '*BackendIntegrationTest.OpenAPI*' bootJar spotlessCheck
```

Exit 0; BUILD SUCCESSFUL in 23s. 8 tests, 0 failures/errors/skips. `bootJar`, `spotlessCheck`
and `git diff --check` pass. No full-suite rerun per root scope, no Git or deploy mutations.

## Root final gate

`JAVA_HOME=JDK21 DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew --no-daemon check bootJar` — PASS in 24s.
Full suite: 44 tests, 0 failures/errors/skips. Log: /private/tmp/yarumo-calendar-backend-final.log.
Independent strict recheck PASS, no open P0/P1/P2. Tester cancellation/auth expiry fixture concern
resolved with a fixed ancient UTC expiration; no production auth behavior changed.

Publishing preflight: gh viewerPermission READ; SSH dry-run push denied to rurkk for upstream repo.
No upstream write/deploy occurred. Local completed feature can be committed; upstream deployment
requires repository write access. This is a GitHub authorization limitation, not an auto-review rejection.
