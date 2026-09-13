# Commit conventions

Format:

```
<type>(<scope>): <subject>            # imperative, <= 72 chars, no full stop

<body>                                # what changed and why, wrapped at 72

Milestone: N
Verified: <what was run and its result>
```

## Types

| Type | Use for |
| --- | --- |
| `feat` | A new capability that is complete and verified |
| `fix` | A correction to existing behaviour |
| `test` | Tests only |
| `refactor` | Behaviour-preserving restructuring |
| `perf` | A change whose purpose is to reduce CPU, memory, battery or network use |
| `docs` | Documentation only |
| `build` | Build system, dependency or CI changes |
| `chore` | Repository hygiene that does not fit elsewhere |

## Scopes

`core`, `app`, `data`, `receiver`, `providers`, `twilio`, `ui`, `ci`, `docs`,
`deps`.

## Rules

- One milestone per commit. If the subject needs "and" three times, it is two
  commits.
- Never describe unimplemented behaviour as implemented. If a commit adds a
  seam without an implementation, say so in the body.
- Never commit a change that has not been compiled or tested where it could
  have been. State in `Verified:` exactly what ran.
- No secrets, tokens, phone numbers, keystores or `local.properties`.
- Reference the milestone in the trailer so the commit can be tied back to
  [MILESTONES.md](MILESTONES.md).

## Examples

```
feat(core): add deterministic number router with auditable decisions

NumberRouter resolves an inbound message to exactly one agent for one
registered number, or to a controlled response or rejection. It performs no
network calls, reads no credentials and mutates nothing, so the decision is
reproducible in tests and auditable in production.

Milestone: 1
Verified: tools/local-verify/run.sh — 90 tests, 90 passed
```

```
build(ci): verify core tests, APK, AAB and lint on every push

The Android modules cannot be built in the development sandbox, so the
workflow runs assembleDebug, assembleRelease, bundleRelease, lint and unit
tests on GitHub Actions.

Milestone: 1
Verified: workflow run for the pushed commit
```
