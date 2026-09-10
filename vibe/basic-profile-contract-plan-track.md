# Basic profile backend contract — T-002

Status: Gate I ready; writes stopped for independent T/V and root final gates.

- Additive Liquibase 009 accepts `profile`, enforces one row per owner and forbids profile tombstones at DB level. Existing rows/kinds unchanged; migration/backup expected changeset count updated 9→10. Existing TRUNCATE lock order retained.
- All five data routes negotiate capability `profile`. Read filtering occurs before cursor/offset construction. POST rejects profile tombstones for capable and incapable callers before ledger/revision, checks actual authenticated owner deterministic UUID, canonical payload identity and strict explicit-null schema. Stale base revision stays 409; client applies server singleton rather than a second profile. Clearing retains an empty snapshot.
- Validator enforces schemaVersion 1, nonnegative int64 updatedAt, exact enums, real ISO birthDate 1900…today UTC with injected Clock, nullable numeric bounds, trimmed 2000 Unicode code-point constraints, unique sorted canonical equipment IDs.
- AI captures a typed saved-owner profile under existing catalog→head locks and expected revision check. Age is computed in full years on UTC request date. Provider DTO excludes exact DOB, syncId and ownerId; measurements and foreign records are excluded. InBody omits profile. Existing post-provider revision/session recheck retained.
- Fixture SHA-256: `1bec288ad8d841efaf645af13ac5ea1cbe2b53c841846589c8101cfe3f524ed6`; test verifies owner-7 specimen while HTTP tests use actual authenticated UUID owners.
- `compileTestKotlin`: PASS, JDK 21. First focused run had one test-only expectation mismatch after seeding an InBody profile; corrected expected retained row count and asserted no measurement write.
- Final targeted `spotlessApply test --tests '*BackendIntegrationTest.profile*' --tests '*BackendIntegrationTest.calendar*' --tests '*BackendIntegrationTest.notes*' --tests '*AiIntegrationTest*' --tests '*AiProfileContextTest*'`: PASS, 25 tests, 0 failures/errors/skips, 23s. Log `/private/tmp/profile-targeted.log`. Local Colima PostgreSQL Testcontainers; no real provider/network credentials.
- Coverage: capability before pagination, mixed tombstone rejection with unchanged snapshot/ledger/revision, wrong owner/alternative ID/uppercase aggregate ID, required-null fields, enum/date/numeric/codepoint/sorted-equipment bounds, singleton clear/stale conflict, UTC leap-day age, unknown age, provider fake redaction, foreign profile/measurement exclusion, InBody no profile, stale response discard, existing AI/CAL/notes paths.
- API reference and OpenAPI capability descriptions updated. Root owns independent review and final full `check`/`bootJar`; writer did not run those gates or publish changes.

## Root final acceptance

Gate T/V PASS; sole P2 localized missing-capability rejection assertions added before full run.
Full check/bootJar PASS36s:72 tests,0 failures/errors/skips. Log
/private/tmp/yarumo-profile-backend-final-fixed.log. First full run exposed existing admin expiry
fixture using DB now-minus-one-second against JVM clock; replaced only that expired fixture timestamp
with year2000, matching the earlier session fixture stabilization. No production auth change.
Fixture parity retained. 009 adds profile kind, singleton and no-tombstone constraints; rollback
retains these data protections rather than downgrading stored profiles. AI projection excludes
DOB/identity/measurements and is absent from InBody. No provider call/deploy; upstream remains READ.
