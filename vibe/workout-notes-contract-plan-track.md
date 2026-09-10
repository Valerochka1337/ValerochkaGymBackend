# Workout notes contract — T-002 tracker

- Gate I: implementation stable; writer stopped after targeted verification.
- Frozen fixture copied unchanged: SHA-256 `5a98f491bbb658c76e47933b49b50041fe1f1a16618b9cadfcf1befdbd22818b`.
- Added optional trimmed 2000-code-point set note and personal `exercise_hint`, canonical UUID, strict payload and additive changelog 008.
- Capability intersection covers snapshot/changes before cursors/list/single; only set notes project away, legacy workout note max10000 unchanged.
- Unsupported hint POST fails before ledger; incapable annotated upsert/tombstone fails atomically with 409. Compatible writes and exact retries preserved.
- STANDARD UUID exception is scoped to hints; new/changed live hints validate owner/public live nonarchived exercise; existing hints survive later deletion.
- Targeted compile and spotlessApply passed. Notes + CAL-01 + OpenAPI: 11 tests passed; final notes + CAL-01 + Android wire fixtures: 12 tests passed, zero failures/errors.
- Coverage includes projection shape across four GET surfaces with no capability/CAL-only, mixed rejection ledger/revision counts, compatible writes, capable tombstone and exact retry, hidden-kind cursor, owner isolation, STANDARD guard/archive, orphan retention, invalid reference, canonical hint UUID, Unicode 2000/2001, null/nonstring fields and legacy10000.
- Updated API docs and generated OpenAPI capability descriptions (retained stable servers and equivalent schema ordering).
- Full backend suite/bootJar and independent T-003 review belong to root; not run by writer. Existing fixture TRUNCATE lock ordering preserved.

## T-003F consolidated test follow-up

- Added direct incapable empty→nonempty and nonempty→nonempty 409/code assertions; ledger, revision and stored snapshot remain unchanged.
- Added stale hint conflict in a mixed batch with unchanged snapshot/ledger and no collateral exercise update.
- Added explicit hint tombstone visibility in capable snapshot/changes, hiding in legacy snapshot/changes, no cursor, 404 single/empty list, and unchanged exercise record.
- `spotlessApply test --tests '*BackendIntegrationTest.notes*'`: 4 tests passed, zero failures/errors. Production code unchanged. Writes stopped for root final checks.

## Root final acceptance

Gate T/V PASS, no production findings. P2 focused assertions added and notes filter4 tests passed.
Full check/bootJar initially found two stale migration-count expectations8→9 in backup/changeset
tests; root updated only those expectations. Final full check/bootJar PASS32s,68 tests,
0 failures/errors/skips; log /private/tmp/yarumo-notes-backend-final-fixed.log.
Fixture parity unchanged. Additive008 migration preserves data; rollback is forward-compatible:
retain columns/payloads and capability guards, never strip persisted annotations to downgrade.
No deployment performed; upstream rights remain READ.
