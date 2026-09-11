# Live Coach backend — tracker

Base origin/main 4cfe4f1, feature branch feat/live-coach. Provider/controller/server model config and HTTP contract implemented and prepared for pull request. No deployment or live secret change.

## Verification

- Targeted CoachTurnServiceTest, HttpCoachTurnProviderTest, HttpOpenAiChatCompletionsProviderTest, AiActionServiceTest: pass.
- HTTP AiIntegrationTest: pass after setting the existing Colima Docker socket and resolving the new controller bean name collision with the journal controller.
- Final `DOCKER_HOST=unix:///Users/valerochka1337/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew test bootJar spotlessCheck`: pass; 180 tests, 0 failures/errors/skips, 15 classes.
- `python3 -m unittest discover -s scripts/tests -p test_ai_config.py`: pass, 9 tests.
- `git diff --check`: pass.

Independent review: pass after closing one P1 (coach-specific 512KiB ingress) and three P2s (service-level response sanitizer, finish_reason/payload consistency, delivery configuration limits before write). Added boundary/regression tests and recheck found no remaining P0/P1/P2 in this slice.

A real selected-model probe requires this backend branch to be deployed/configured. The Android settings action runs the synthetic check without user workout mutations. No production provider request was issued by these tests.
