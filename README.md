# Secure WhatsApp AI Architecture

A modular Android application that connects WhatsApp conversations to
configurable AI agents through Twilio, with per-number user types
(**Doctor**, **Patient**, **Common user**), per-number agents, interchangeable
AI providers, and strict isolation between numbers, agents, conversations,
credentials and logs.

```
WhatsApp user → Twilio → Android app → AI provider → Android app → Twilio → WhatsApp user
```

## Status: milestone 1 (in progress)

This repository is built incrementally. **Milestone 1 delivers the repository
skeleton and the deterministic domain core.** The app installs and runs, and
shows exactly which capabilities exist. It does not send or receive a WhatsApp
message, call an AI provider, or persist anything yet — those are milestones 5,
4 and 2 respectively, and the app marks them as unavailable rather than
simulating them.

Read [docs/MILESTONES.md](docs/MILESTONES.md) for the plan and the verification
record, and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design.

## Documentation

| Document | Contents |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, modules, routing, isolation, providers, resource usage |
| [docs/RECEIVER_REQUIREMENTS.md](docs/RECEIVER_REQUIREMENTS.md) | Why Android cannot be a webhook endpoint, and what you must deploy |
| [docs/SECURITY.md](docs/SECURITY.md) | Credentials, inbound authentication, idempotency, redaction, release |
| [docs/TESTING.md](docs/TESTING.md) | Test levels and traceability to the product scenarios |
| [docs/MILESTONES.md](docs/MILESTONES.md) | Milestone plan, status and per-milestone verification records |
| [docs/LOCAL_VERIFICATION.md](docs/LOCAL_VERIFICATION.md) | What can and cannot be verified in the development sandbox |
| [docs/BRANCHING.md](docs/BRANCHING.md) | Branch strategy, push rules, PR expectations |
| [docs/COMMIT_CONVENTIONS.md](docs/COMMIT_CONVENTIONS.md) | Commit message format and rules |

## Requirements to run the finished product

The finished application is not self-contained: WhatsApp messaging through
Twilio requires infrastructure outside the app. These are prerequisites, and
the app blocks activation until each one is validated rather than pretending it
is satisfied.

- A Twilio account with a WhatsApp-enabled sender (Sandbox for development;
  an approved WhatsApp Business sender, with templates and user consent, for
  business-initiated production messages).
- A publicly reachable HTTPS receiver. An Android app cannot serve a Twilio
  webhook; see [docs/RECEIVER_REQUIREMENTS.md](docs/RECEIVER_REQUIREMENTS.md).
- API credentials for at least one supported AI provider (Gemini, OpenAI,
  Anthropic, or any OpenAI-compatible endpoint).
- Network connectivity whenever a message is processed.

## Building

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK (unsigned unless a keystore is configured)
./gradlew :app:bundleRelease      # release AAB
./gradlew :core:test              # deterministic domain tests
./gradlew :app:testDebugUnitTest  # Android unit tests
```

Release signing is read from the environment and never stored in the
repository:

```
SECUREWA_KEYSTORE_PATH         path to the keystore
SECUREWA_KEYSTORE_PASSWORD     keystore password
SECUREWA_KEY_ALIAS             key alias
SECUREWA_KEY_PASSWORD          key password
```

Without them the release artifacts build unsigned, which proves the release
variant compiles but is **not** distributable.

## Verifying the domain core without Gradle

The development sandbox for this project cannot reach Maven Central, Google
Maven or the Gradle distribution service, so the Android build is verified in
CI. The dependency-free `:core` module can be verified anywhere:

```bash
tools/setup-toolchain.sh     # installs a JDK and kotlinc outside the repository
tools/local-verify/run.sh    # compiles :core and runs its tests
```

## Repository hygiene

Every push is checked by `tools/ci/secret_scan.py` (no credentials, keystores,
build output or oversized files) and `tools/ci/repo_hygiene.py` (no
machine-specific paths, wrapper intact, documentation present), in addition to
the Gradle build and tests in `.github/workflows/verify.yml`.

## Licence

Not yet specified.
