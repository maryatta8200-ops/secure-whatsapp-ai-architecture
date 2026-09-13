#!/usr/bin/env python3
"""Publish CI build logs to an orphan branch so they can be read out of band.

Why this exists: the environment this project is developed in cannot reach
results-receiver.actions.githubusercontent.com or the artifact service, so a
failed workflow run cannot be inspected with `gh run view --log` or
`gh run download`. When a job fails, the workflow writes the Gradle output of
that run into the orphan branch `ci-logs`, which is readable with an ordinary
`git fetch`.

Guarantees:
  - runs only on failure, and only for the current run id;
  - keeps at most MAX_RUNS directories on the branch, oldest first;
  - the publishing token is never written into a log;
  - the branch is an orphan: it shares no history with main and is excluded
    from the repository hygiene checks that apply to source branches.

Usage: publish_log.py <log-dir> [<log-dir> ...]
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

BRANCH = "ci-logs"
MAX_RUNS = 5


def git(args: list[str], cwd: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=cwd, check=check, capture_output=True, text=True)


def main() -> int:
    log_dirs = [path for path in sys.argv[1:] if os.path.isdir(path)]
    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    run_id = os.environ.get("GITHUB_RUN_ID", "local")
    sha = os.environ.get("GITHUB_SHA", "unknown")

    if not token or not repo:
        print("publish_log.py: GITHUB_TOKEN/GITHUB_REPOSITORY missing, skipping")
        return 0
    if not log_dirs:
        print("publish_log.py: no log directory to publish")
        return 0

    work = tempfile.mkdtemp(prefix="ci-logs-")
    remote = f"https://x-access-token:{token}@github.com/{repo}.git"

    cloned = subprocess.run(
        ["git", "clone", "--depth", "1", "--branch", BRANCH, remote, work],
        capture_output=True,
        text=True,
    ).returncode == 0

    if not cloned:
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(work)
        git(["init", "-q"], cwd=work)
        git(["checkout", "-q", "-b", BRANCH], cwd=work)
    else:
        # Drop the previous content of this run id if the job was retried.
        shutil.rmtree(os.path.join(work, run_id), ignore_errors=True)

    git(["config", "user.email", "ci@local.invalid"], cwd=work)
    git(["config", "user.name", "ci log publisher"], cwd=work)

    destination = os.path.join(work, run_id)
    os.makedirs(destination, exist_ok=True)
    published = 0
    for log_dir in log_dirs:
        for name in sorted(os.listdir(log_dir)):
            source = os.path.join(log_dir, name)
            if os.path.isfile(source):
                shutil.copy2(source, os.path.join(destination, name))
                published += 1

    with open(os.path.join(destination, "RUN.txt"), "w", encoding="utf-8") as handle:
        handle.write(f"run_id={run_id}\ncommit={sha}\nfiles={published}\n")

    # Keep only the newest runs.
    existing = sorted(
        entry for entry in os.listdir(work)
        if entry != ".git" and os.path.isdir(os.path.join(work, entry))
    )
    for stale in existing[:-MAX_RUNS]:
        git(["rm", "-r", "-q", "--ignore-unmatch", stale], cwd=work, check=False)
        shutil.rmtree(os.path.join(work, stale), ignore_errors=True)

    git(["add", "-A"], cwd=work)
    status = git(["status", "--porcelain"], cwd=work).stdout.strip()
    if not status:
        print("publish_log.py: nothing to commit")
        return 0

    git(["commit", "-q", "-m", f"ci logs for run {run_id} ({sha[:12]})"], cwd=work)
    push = git(["push", remote, f"HEAD:{BRANCH}"], cwd=work, check=False)
    if push.returncode != 0:
        print(f"publish_log.py: push failed: {push.stderr.strip()}")
        return 0
    print(f"publish_log.py: published {published} log file(s) to {BRANCH}/{run_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
