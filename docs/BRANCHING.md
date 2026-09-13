# Branching and repository strategy

## Branches

| Branch | Role |
| --- | --- |
| `main` | Integration branch. Receives only verified milestones through a pull request. |
| `arena/<session-id>-<slug>` | The working branch for one agent session. All development for a session happens here. |

The working branch is never rebased after it has been pushed and reviewed,
because the commit SHAs recorded in [MILESTONES.md](MILESTONES.md) must stay
resolvable. Fix-forward with new commits; history rewriting is only acceptable
before the first push of a session.

## Flow

```
arena/…  ──commit (milestone N, verified locally)──►  push
                                                      │
                                              CI: hygiene + core + android
                                                      │
                                          green → open PR → merge to main
```

## Rules

1. **One milestone per commit.** A commit contains the work of exactly one
   independently verifiable milestone, with a message naming it.
2. **Verify before push.** Local verification (`tools/local-verify/run.sh`) and,
   for Android-affecting changes, the CI workflow must be green before the
   commit is described as verified. If CI fails, the milestone is reported as
   failing, not as done.
3. **Push only after verification, then confirm the remote.** After pushing, the
   remote SHA is compared with the local SHA (`git rev-parse HEAD` vs
   `git ls-remote origin <branch>`). A milestone is not "synchronized" until
   they match.
4. **No secrets, ever.** `tools/ci/secret_scan.py` runs on every push.
5. **No unrelated changes.** Local toolchains, editor state, build output,
   keystores and `local.properties` stay out of the repository
   (`tools/ci/repo_hygiene.py` enforces this).
6. **No large artifacts.** Binaries, APKs, AABs and datasets are produced by CI
   or by a local build; they are not committed.

## Pull requests

A pull request from the session branch to `main` contains:

- what the milestone delivers and what it explicitly does not;
- the local verification output (test counts);
- the CI run URL and result;
- the list of limitations or follow-ups.

## Tags

Milestones are tagged `m1`, `m2`, ... on `main` after the merge, pointing at the
verified commit.
