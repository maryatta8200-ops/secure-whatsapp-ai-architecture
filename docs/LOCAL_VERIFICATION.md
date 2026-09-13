# Local verification

## What the development sandbox can and cannot do

The sandbox this project is developed in has a restricted network allowlist.

| Reachable | Not reachable |
| --- | --- |
| github.com (git, API), pypi.org, registry.npmjs.org | `dl.google.com` (Android SDK, Google Maven), `repo1.maven.org` (Maven Central), `services.gradle.org` (Gradle distributions), release-assets.githubusercontent.com |

Consequences, stated plainly:

- **An APK or AAB cannot be built in the sandbox.** The Android Gradle Plugin,
  the Android SDK platform and build-tools, and every AndroidX, Compose, Room
  and WorkManager artifact are unavailable, so Gradle cannot even configure the
  Android modules here.
- **The dependency-free `:core` module can be fully compiled and executed**
  locally, because a JDK and the Kotlin compiler can be obtained from PyPI and
  npm.

The Android build is therefore verified by CI on GitHub Actions, which has
unrestricted network access and a pre-installed Android SDK. Both channels run
the same source; CI additionally runs the same `:core` tests on a real JUnit
runtime.

## Setting up the local toolchain

```bash
tools/setup-toolchain.sh      # installs a JDK and kotlinc into ~/.local/toolchain
```

Nothing is installed into the repository; the directory is git-ignored.

## Running the domain tests locally

```bash
tools/local-verify/run.sh     # compiles :core main + test sources, runs them
tools/local-verify/run.sh NumberRouter   # only tests whose class name matches
```

How it works:

1. `core/src/main/kotlin` and `core/src/test/kotlin` are compiled with
   `kotlinc` (the same sources Gradle compiles in CI).
2. The test sources use the JUnit 4 API. Because the JUnit artifact cannot be
   downloaded here, `tools/local-verify/stubs` provides a minimal
   source-compatible shim (`org.junit.Test`, `org.junit.Assert`) **for local
   runs only**. It is excluded from every Gradle source set and from the app.
3. `tools/local-verify/runner` discovers `*Test` classes, runs every `@Test`
   method with a fresh instance, prints per-test results and exits non-zero on
   any failure.

The shim contains no test logic: it only decides what counts as a failure. Every
assertion executed locally is an assertion written in the project's own test
sources against the project's own production code.

## Reading CI failures from the sandbox

`results-receiver.actions.githubusercontent.com` and the artifact service are
also blocked, so `gh run view --log` and `gh run download` do not work here.
When a job fails, the workflow publishes the Gradle output of that run (and
`app/build/reports`, which contains the full lint report) to the orphan branch
`ci-logs`, one directory per run id, newest five kept:

```bash
git fetch origin +refs/heads/ci-logs:refs/remotes/origin/ci-logs
git ls-tree -r --name-only origin/ci-logs
git show origin/ci-logs:<run-id>/android-unit-tests.log
```

`ci-logs` shares no history with `main` and is excluded from the hygiene checks
that apply to source branches. It is a diagnostics channel, not part of the
product; delete it (`git push origin --delete ci-logs`) when it is no longer
needed.

## What "verified locally" means for a milestone

A milestone may claim local verification only when, for that commit:

- `tools/local-verify/run.sh` exits `0`, and
- the test count and pass count are recorded in [MILESTONES.md](MILESTONES.md).

A milestone may claim Android verification only when the CI `android` job is
green for the pushed commit SHA.
