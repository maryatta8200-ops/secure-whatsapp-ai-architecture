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
| 1 | Repository and domain core | Gradle multi-module skeleton, version catalog, Gradle wrapper, CI workflow, secret and hygiene scanners, documentation set, and the `:core` domain module: user types, E.164 validation, `NumberRouter`, Twilio signature validation, replay guard, idempotency keys, redaction, rate limiting, retry policy, health states, capability registry. Minimal Compose app shell that reports capability status. | ✅ verified — commit `4633aae`, CI run 34739654935 |
| 2 | Local persistence | Room schema (numbers, user types and history, agents, routing rules, providers, credential slots, model configuration, conversations, messages, Twilio and channel configuration, inbound receipts, outbound attempts, agent events, application log), DAOs, foreign keys, indexes, unique constraints and deletion policies, verified by 18 Robolectric tests against real SQLite. Database encryption of message bodies is deferred to milestone 3, which introduces the key it needs. | ✅ verified — commit `e018ee9`, CI run 34741226163 |
| 3 | Application lock and credential vault | PBKDF2-HMAC-SHA256 passphrase derivation, master key wrapped with the derived key, AES-256-GCM credential sealing bound to the credential slot, Android Keystore platform layer, lock screen gating the whole app, password rotation that re-wraps the master key. Backup/restore deferred to milestone 9. | ✅ verified — commit `94c7e12`, CI run 34742754401 |
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

## Milestone 2 record

| Item | Value |
| --- | --- |
| Commits | `f2f9005` persistence layer, `5b21d9d` column naming fix, `c772807` Robolectric SDK fix, `e018ee9` fixture fix |
| Verified commit | `e018ee9` |
| Modules | `:data` (Android library, Room + KSP) added to the build |
| Local domain verification | `tools/local-verify/run.sh` — 90 tests, 90 passed |
| CI run | https://github.com/maryatta8200-ops/secure-whatsapp-ai-architecture/actions/runs/34741226163 |
| CI `android` | success — `:data:testDebugUnitTest` (18 tests), `:app:testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `bundleRelease`, `:app:lintDebug`, `:data:lintDebug` |
| Remote SHA check | local `e018ee9` == remote `e018ee9` |

### Guarantees encoded in the schema

- A registered number is the tenant boundary: `e164` is unique, so the same
  number cannot be registered twice (asserted by test).
- User types are reference rows seeded from the domain enum and referenced by
  foreign key, so an invalid user type is rejected by SQLite (asserted by test).
- Deleting configuration never deletes history: agents and numbers cascade to
  the configuration they own, and `SET_NULL` for conversations, messages and
  audit rows (asserted by test).
- Inbound receipts and outbound attempts carry unique idempotency keys, and a
  receipt can be claimed exactly once (asserted by test).
- Reads are bounded: messages come from a window or a page, and log retention
  is enforced with a cutoff delete (asserted by test).

### Defects found by verification

1. `@PrimaryKey(name = "storage_key")` named the index rather than the column,
   so every query against `user_types` failed at build time. Room's query
   validation caught it.
2. Robolectric was asked for an API level it does not ship; all tests in the
   class failed with `UnknownSdk`.
3. Six tests inserted an agent without the provider and model rows its foreign
   key requires, and one log-retention assertion had the arithmetic backwards.

### Known limitation carried forward

Message bodies are stored as plain text. The database is protected by
`allowBackup=false`, the data extraction rules and device encryption, but the
bodies are not sealed with an application key yet. That ships with milestone 3.

## Milestone 3 record

| Item | Value |
| --- | --- |
| Commits | `bf8d580` vault and lock, `2ab7ce9` Room encapsulation fix, `94c7e12` scaffold fix |
| Verified commit | `94c7e12` |
| Local domain verification | `tools/local-verify/run.sh` — 122 tests, 122 passed (2026-09-13 sandbox) |
| Additional local verification | 20 vault tests compiled and run with kotlinc against the local JUnit shim |
| CI run | https://github.com/maryatta8200-ops/secure-whatsapp-ai-architecture/actions/runs/34742754401 |
| CI `android` | success — `:core:test`, `:data:testDebugUnitTest`, `:app:testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `bundleRelease`, `lintDebug` (0 lint errors) |
| Remote SHA check | verified locally after each push |

### The three layers

1. **Passphrase.** PBKDF2-HMAC-SHA256, 210,000 iterations, 16 byte per-install
   salt, 256 bit output. The password is handled as a `CharArray` end to end, is
   never converted to a String inside the vault, and is never stored.
2. **Master key.** 32 random bytes, wrapped with the derived key using
   AES-256-GCM. Credentials are sealed with the master key with the credential
   slot id as associated data, so a ciphertext cannot be moved between slots.
3. **Platform key.** The wrapped master key is sealed again with a
   non-extractable AES-256-GCM key in the Android Keystore and written to a file
   with an atomic rename, so a copy taken off the device is useless and a crash
   mid-write cannot corrupt the envelope.

Rotating the password re-wraps the master key; every stored credential stays
readable, so changing the application password does not require re-entering
provider keys.

### Failure states that are reported separately

`UnlockResult` distinguishes `WrongPassphrase`, `NoVault`, `PlatformFailure`
(the device key cannot open the envelope, for example after a Keystore reset)
and `CorruptEnvelope`. They have different remedies and are never collapsed into
a single "failed".

### Defects found by verification

1. The fsync in `FileVaultStorage.write` reopened the file with
   `File.outputStream()`, which truncates: every read returned an empty array.
   Caught by the local vault tests, not by inspection.
2. `AppContainer` referenced `androidx.room.Room`, which `:app` does not depend
   on. Room stays inside `:data`.
3. A delegated property cannot be smart cast, so the lock screen's `when` branch
   on `LockState.Message` did not compile.
4. Lint `UnusedMaterial3ScaffoldPaddingParameter`: adding the lock screen left
   two Scaffolds, and the inner one ignored its padding. Screen chrome now
   belongs to `AppRoot` alone.

### Known limitation carried forward

No UI exists yet for entering provider credentials; the vault is exercised by
the lock screen and by tests. Encrypted backup and restore is milestone 9.

### What milestone 1 deliberately does not do

- It does not send or receive a single WhatsApp message: no Twilio client exists yet.
- It does not call any AI provider.
- It does not persist anything.
- It does not display a simulated dashboard, message list or connection status.
