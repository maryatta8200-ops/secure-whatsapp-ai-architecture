# Testing strategy

Tests are grouped by what they can prove, and they only ever assert on real
behaviour. A test that merely constructs a request and asserts the request was
constructed is not counted as verification.

## Levels

| Level | Runs | What it covers |
| --- | --- | --- |
| Domain unit tests | `./gradlew :core:test` and `tools/local-verify/run.sh` | Deterministic logic: user types, E.164, routing, signatures, idempotency, redaction, rate limits, retries. |
| Android unit tests | `./gradlew :app:testDebugUnitTest` | View model and capability logic that does not need a device. |
| HTTP adapter tests | `./gradlew :data:test` (milestone 4+) | Real HTTP against a local mock server: status codes, auth headers, timeouts, rate limits, retries, parsing, error bodies. |
| Persistence tests | `./gradlew :data:test` (milestone 2+) | Room migrations, constraints, cascade behaviour, idempotency key uniqueness. |
| Instrumented tests | device/emulator, run on demand | Android lifecycle behaviour, WorkManager, process death recovery, encryption. |
| CI checks | `tools/ci/*.py` | Secrets, repository hygiene, documentation presence. |

Instrumented tests are run on demand, never concurrently with a build, because
emulator work dominates CPU usage on a small runner.

## Traceability

The numbered scenarios from the product specification map to milestones as
follows. Each row is only marked done when the test exists **and passes**.

| Scenario | Milestone | Status |
| --- | --- | --- |
| Number registration, user-type assignment, validation and editing | 2, 7 | planned |
| Agent creation, editing, deletion, multiple agents per number | 2, 7 | planned |
| Provider selection, credential encryption, credential validation | 3, 4 | planned |
| Number routing, user-type-aware routing, agent routing, rule priority | 1 (engine), 6 (wiring) | engine covered (`NumberRouterTest`) |
| Conversation isolation, shared-provider isolation | 2, 6 | planned |
| Message idempotency, duplicate inbound handling | 2, 6 | keys covered (`IdempotencyKeysTest`) |
| Inbound request validation and replay protection | 6 | signature and replay covered (`TwilioSignatureValidatorTest`, `ReplayGuardTest`) |
| Outbound retry behaviour | 5, 6 | policy covered (`RetryPolicyTest`) |
| Receiver availability and queue behaviour | 6 | planned |
| Configuration revision consistency | 2, 6 | snapshots covered by `AgentRouteConfig` |
| Rate limiting and cost controls | 6 | policy covered (`RateLimiterTest`) |
| Sensitive-data redaction | 1 | covered (`RedactorTest`) |
| Android lifecycle and background execution | 6, 8 | planned |
| Release build security checks | 9 | planned |
| GitHub synchronization and repository hygiene | 1 | covered in CI (`secret_scan.py`, `repo_hygiene.py`) |

## Rules for tests in this repository

1. Provider tests use provider-approved test credentials, sandbox
   destinations, local deterministic fixtures or a controlled integration
   environment. A live production send in a test is a bug.
2. An integration test asserts on the HTTP status, the authentication header,
   the signature, the retry behaviour, the timeout and the parsed body — not
   on a locally fabricated success.
3. A test never reports success because an exception was swallowed. Every
   failure path has an assertion.
4. Tests that need the network are separated from tests that do not, so the
   offline suite stays runnable anywhere.
5. Every test name states the behaviour it protects; `test1`, `it works` and
   similar are rejected in review.
