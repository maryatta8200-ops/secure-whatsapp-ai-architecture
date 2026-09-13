# Milestones

Every milestone is independently verifiable. A milestone is only marked
**verified** when the checks listed for it are green **for the exact commit
recorded in the table**, and that commit has been pushed to the remote and
confirmed to exist there.

## Verification channels

| Channel | What it proves | Where it runs |
| --- | --- | --- |
| Local domain verification | The dependency-free `:core` module compiles and its unit tests execute. | Development sandbox (`tools/local-verify/run.sh`) |
| CI `hygiene` | No secrets, no build output, no machine-specific paths; required docs present. | GitHub Actions |
| CI `core` | `:core:test` (JUnit 4) on a real JVM. | GitHub Actions |
| CI `android` | `:app:testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `bundleRelease`, `lintDebug` against the real Android SDK. | GitHub Actions |

The development sandbox cannot build Android: `dl.google.com`,
`repo1.maven.org`, `services.gradle.org` and GitHub release assets are not
reachable from it, so the Android Gradle Plugin, the Android SDK and every
AndroidX dependency cannot be fetched. This is recorded in
[LOCAL_VERIFICATION.md](LOCAL_VERIFICATION.md) and is the reason the Android
channel runs in CI rather than locally.

## Milestone plan

| # | Milestone | Contents | Status |
| --- | --- | --- | --- |
| 1 | Repository and domain core | Gradle multi-module skeleton, version catalog, Gradle wrapper, CI workflow, secret and hygiene scanners, documentation set, and the `:core` domain module: user types, E.164 validation, `NumberRouter`, Twilio signature validation, replay guard, idempotency keys, redaction, rate limiting, retry policy, health states, capability registry. Minimal Compose app shell that reports capability status. | ⏳ in progress — see record below |
| 2 | Encrypted local persistence | Room schema (numbers, user types and history, agents, routing rules, providers, credential slots, model configuration, conversations, messages, Twilio and channel configuration, inbound receipts, outbound attempts, agent events, application log), DAOs, foreign keys, indexes, unique constraints, migrations, migration tests. | planned |
| 3 | Application lock and credential vault | Passphrase-based application lock, Android Keystore-backed key, encrypted credential slots, encrypted backup/restore primitives. | planned |
| 4 | AI provider adapters | Gemini, OpenAI, Anthropic and OpenAI-compatible adapters behind one provider interface; configuration snapshots; HTTP-level tests with a local mock server (status handling, auth headers, timeouts, parsing, rate limits). | planned |
| 5 | Twilio messaging adapter | Authenticated Twilio REST client for outbound WhatsApp, status callback handling, connection-state checks. | planned |
| 6 | Inbound receiver and message pipeline | `ReceiverClient` seam with the Twilio Functions + Sync implementation, pull/acknowledge/health, durable inbound processing, routing hand-off, provider call, outbound send, retries, idempotency, audit events; WorkManager scheduling. Includes the deployable Twilio Function source. | planned |
| 7 | Number and agent management UI | Numbers, agents, providers, routing rules, per-number user type, real connectivity tests, enable/disable. | planned |
| 8 | Messages, logs and dashboard | Conversation view, delivery state, audit log viewer with redaction, dashboard built from persisted state only. | planned |
| 9 | Release hardening and backup/restore | R8 rules validated by tests, release signing configuration, release security checks, encrypted export/import. | planned |

## Milestone 1 record

| Item | Value |
| --- | --- |
| Branch | `arena/01a098fa-secure-whatsapp-ai-architectur` |
| Commits | `ba15c0c` domain core, `f863589` CI log channel, `1bbd398` import fix, `b5379ac` package alignment |
| Verified commit | `b5379ac` |
| Modules | `:core` (pure Kotlin/JVM), `:app` (Android, Compose) |
| Local domain verification | `tools/local-verify/run.sh` — 90 tests, 90 passed, 0 failed (kotlinc compile + JVM execution) |
| CI run | https://github.com/maryatta8200-ops/secure-whatsapp-ai-architecture/actions/runs/34739654935 |
| CI `hygiene` | success — secret scan clean (64 tracked files), repository hygiene clean |
| CI `core` | success — `:core:test` on JUnit 4 |
| CI `android` | success — `:app:testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `bundleRelease`, `lintDebug` (0 lint errors) |
| Remote SHA check | local `b5379ac` == remote `b5379ac` |
| Known limitations | The app shell renders capability status only. Persistence, providers, Twilio and messaging are marked unavailable in the UI and are not wired to anything. The release APK/AAB is unsigned unless `SECUREWA_KEYSTORE_PATH` and friends are supplied; R8 is disabled until milestone 9. |

### Defects found by verification, not by inspection

1. `FeatureRegistry` was imported from the wrong package and generated
   resources were referenced as `com.securewa.app.R` — the app module did not
   compile.
2. Lint `MissingClass` on both manifest entries, because the manifest resolved
   classes against the namespace `com.securewa.architecture` while the sources
   declared `com.securewa.app`.

Both were found in the Gradle output published by the CI failure channel and
both were fixed at the root (namespace and source packages now agree) rather
than suppressed.

### What milestone 1 deliberately does not do

- It does not send or receive a single WhatsApp message: no Twilio client exists yet.
- It does not call any AI provider.
- It does not persist anything.
- It does not display a simulated dashboard, message list or connection status.
