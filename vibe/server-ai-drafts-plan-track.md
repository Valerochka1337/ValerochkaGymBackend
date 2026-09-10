# AI-01 backend T-002 / T-003

2026-09-10. Base c73c411, branch feat/server-ai-drafts. Backend-only writer;
root owns Android, Git publication/deployment and final full-suite Gate V.

## Gate I: PASS, stable handoff

- Frozen fixture `src/test/resources/ai-contract-v1.json` unchanged, SHA-256
  `f76033bf776748a37567c0a26a9c74e8cf13d215c47e2077068c0b679ae6599b`.
  Runtime exercise/InBody output schemas are exact fixture subtrees, tested for equality.
- Authenticated status and two draft endpoints, typed action DTOs and exact validated wrapper.
  No schema migration, records/head/operations writes, request/result ledger, model endpoint,
  provider key read from Android, automatic retry or planner/PDF/proposal scope.
- Context capture/recheck uses catalog→owner locks in separate short transactions. Provider runs
  outside DB transactions; HTTP regressions mutate head/catalog/revoke/delete during fake call.
  Owner/public live catalog only; archived/foreign IDs fail. Existing ready head is required.
- Stateless async response dispatch re-authenticates via BearerFilter. Timeout/error/completion
  cancels the worker; interruption cancels the underlying async exchange. Remote provider/cost
  cancellation is best effort, not promised.
- Fixed env-configured HTTPS OpenAI Chat Completions adapter, strict schemas, stream/store false,
  n=1, required operator text/vision models and max_completion_tokens=2048. No redirects/tools/
  fallback/model list. Exactly one complete non-refusal assistant choice; safe errors only.
- 5s connect, 45s action/provider deadline, 256KiB bounded streaming response subscriber, two
  action slots held from before image/context preparation through provider and final recheck. 6MiB JPEG/8MiB base64/3072px and 10MiB whole request cap; ImageIO explicit memory
  input stream, no disk cache/temp image. Full serialized exercise context bounded to 1MiB.
- Explicit AI env delivery uses production variable names plus AI_API_KEY secret; unset disables.
  Private temporary JSON, no echo, cleanup on both workflow sides and deploy success/failure.
  Atomic .env update; deployment trap restores prior env for early errors too. Tests use dummy
  credentials and mocked docker/SSH-free local deployment, never real .env or live provider.
- docs/api.md, README and runtime-generated OpenAPI updated with original configured server URLs.

## Final verification commands and counts

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew --no-daemon spotlessApply test \
  --tests '*AiIntegrationTest*' \
  --tests '*AiActionServiceTest*' \
  --tests '*HttpOpenAiChatCompletionsProviderTest*' bootJar spotlessCheck
python3 -m unittest discover -s scripts/tests -p 'test_ai_config.py'
bash -n scripts/deploy.sh
git diff --check
```

Final Gradle exit 0, BUILD SUCCESSFUL in 17s: 18 tests, zero failures/errors/skips.
AiIntegrationTest 9, AiActionServiceTest 4, HttpOpenAiChatCompletionsProviderTest 5.
Delivery script suite: 6 tests pass (2.416s); shell syntax and whitespace checks pass.

Evidence covers unconfigured/auth/legacy independence, immutable no-write drafts, owner/public/
archive isolation, pre/post revision barriers, session revoke/account deletion, exact nullable
InBody/finite numbers, malformed/unknown fields, JPEG exact max/+1 dimensions/bytes/base64,
whole-request exact 10MiB/+1 without Content-Length and conflicting understated CL+TE rejection,
strict model/wire fixture, response exact 256KiB/+1, slow headers/body, two accepted/third busy,
cancellation releasing permits, refusal/truncation/error/redirect rejection, env success/rollback/
cleanup/no-echo. Testcontainers applies production Liquibase against isolated PostgreSQL.

During implementation tests exposed stateless async security and a UTF-8 byte-boundary fixture;
both corrected and rerun. Initial test auth token length fixture was corrected to real token bounds.
No full `check` was run: root owns final legacy/full gates on stable diff.

## Remaining external gate and limitations

- Actual provider/model capability and production credentials are operator-owned and unverified.
  Current upstream/deployment access remains blocked by GitHub permissions; no Git push/merge,
  deployment, real provider request, or actual .env/secret read was performed.
- AVAILABLE means valid local configuration, not a live availability probe.
- Android typed Ready(owner, personalRevision, catalogRevision), consent and late-result guards
  remain Android T-004/T-005. Backend does not infer sync readiness from a Unit-returning call.
- No exactly-once billed request or provider retention/cancellation guarantee is made. store:false
  follows the frozen protocol; operator must choose a provider/model compatible with it.

Writes stopped for independent T-006 review and root final checks.

## T-006F review fixes — stable Gate I PASS

- P1: moved the shared two-slot admission boundary into `AiActionService`; both endpoints
  acquire before base64/ImageIO or context capture, and `finally` releases after result validation
  and revision/session postcheck, including exceptions/interruption. Removed the HTTP adapter's
  semaphore so admission is owned once, with no nested permit acquisition.
- Regression holds two actions in a fake provider, then proves a third invalid image and stale
  context both return `ai_busy` before their respective validation/capture errors. Cancelling both
  workers releases admission; repeated image/context failures and a later successful action prove
  no permit leak. Adapter cancellation still has its independent HTTP exchange regression.
- P2: disabled config application removes all six AI keys, including old credentials/model/base
  values, then writes only `AI_ENABLED=false`; unrelated settings remain. Dummy-file regression
  verifies this and temporary-file cleanup.
- P2: raw exercise description length is checked before trimming; trimmed input must be nonblank.
  HTTP regressions cover exactly 2000 characters with surrounding whitespace, 2001, and blank.
- Targeted Gradle `spotlessApply test` (three AI test classes) + `spotlessCheck`: PASS, 20 tests
  (11 integration, 4 domain, 5 provider), zero failures/errors/skips, 18s. Evidence:
  `/private/tmp/yarumo-ai-review-fixes.log`. Python delivery suite: 7 PASS (2.975s).
  `bash -n scripts/deploy.sh` and `git diff --check`: PASS. Initial test compile ambiguity was
  corrected before this successful run. Full final check/bootJar remains root-owned.
- No Git mutation, production secrets, live provider calls, or deployment. Writes stopped for
  narrow review recheck and final root gates.

## Full-gate test isolation correction

Root's first full check exposed a first-test fixture deadlock in BackendIntegrationTest.clean.
AuthService's existing immediate scheduled cleanup locks sessions → email_challenges →
google_nonces → rate_limits within one transaction; the old TRUNCATE took rate_limits before
CASCADE reached sessions, forming a lock-order cycle. This happened at context startup in the
separate legacy test database, not from a remaining AI provider worker.

Both integration fixture resets now explicitly start TRUNCATE with sessions/refresh_tokens,
then email_challenges/google_nonces/rate_limits before users and other tables. The common first
sessions lock serializes cleanup versus reset before either can acquire locks in reverse order.
Production scheduling, auth code, tests, and assertions remain intact; no retry or swallowed
exception was introduced. Targeted validation is recorded below after completion.

Targeted both affected integration suites + spotlessCheck: PASS, 52 tests (41 legacy + 11 AI),
zero failures/errors/skips, 35s. Log `/private/tmp/yarumo-ai-cleanup-isolation.log`.
`git diff --check` PASS. Writes stopped; root owns the justified final full-gate rerun.

## Root final acceptance — 2026-09-10

Gate T PASS; strict Gate V narrow recheck PASS, all three findings resolved.
Full `check bootJar` PASS in31s:64 tests,0 failures/errors/skips. Log:
`/private/tmp/yarumo-ai-backend-final-fixed.log`. Full Python script discovery15 tests PASS
(`/private/tmp/yarumo-ai-delivery-final.log`), including7 AI delivery tests.
The earlier full run found a fixture TRUNCATE/scheduled-cleanup lock inversion; test-only
lock ordering fixed, both integration suites52 tests passed before the successful full run.
No live provider call, secret value read or production deploy. Configuration defaults off.
Backend GitHub READ permission still blocks upstream publication/deploy; code is accepted locally.
Rollback: disable AI and deploy prior image; disabled config purges all six AI keys.
No database migration is introduced by this AI slice; CAL-01 remains its retained predecessor.
