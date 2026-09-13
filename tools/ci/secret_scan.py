#!/usr/bin/env python3
"""Secret scanner for the repository.

Runs in CI over every *tracked* file of the checked out commit. It fails the
build when a file looks like it contains a credential, when a prohibited file
type is tracked, or when an oversized artifact is committed.

The scanner is intentionally conservative on real secrets and deliberately
noisy: a false positive is cheap, a committed credential is not. Genuine
non-secret fixtures (unit test values) are excluded through
tools/ci/secret_scan_allowlist.txt, which is part of the repository and is
reviewed like any other file.

Exit codes: 0 = clean, 1 = findings, 2 = the scanner could not run.
"""

from __future__ import annotations

import fnmatch
import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ALLOWLIST = os.path.join("tools", "ci", "secret_scan_allowlist.txt")

# Files that are binary, generated or vendored and therefore not scanned.
SKIP_SUFFIXES = (
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf",
    ".jar", ".zip", ".gz", ".tgz", ".aar", ".so", ".ttf", ".otf",
    ".wav", ".mp3", ".mp4", ".jks", ".keystore", ".p12", ".der",
)

# Files that must never be tracked at all.
PROHIBITED_PATHS = (
    "local.properties",
    "keystore.properties",
    "signing.properties",
    "google-services.json",
    ".env",
)

PROHIBITED_SUFFIXES = (".jks", ".keystore", ".p12", ".key", ".pem")

MAX_TRACKED_BYTES = 2 * 1024 * 1024

PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("aws-access-key-id", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("github-token", re.compile(r"gh[pousr]_[A-Za-z0-9]{36,}")),
    ("openai-api-key", re.compile(r"sk-[A-Za-z0-9]{32,}")),
    ("anthropic-api-key", re.compile(r"sk-ant-[A-Za-z0-9_-]{32,}")),
    ("google-api-key", re.compile(r"AIza[0-9A-Za-z_\-]{35}")),
    ("slack-token", re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}")),
    ("private-key-block", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("jwt", re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")),
    (
        "assigned-secret",
        re.compile(
            r"(?i)\b(api[_-]?key|apikey|secret|password|passwd|access[_-]?token|auth[_-]?token)\b"
            r"\s*[:=]\s*[\"']([^\"']{12,})[\"']"
        ),
    ),
)


def tracked_files() -> list[str]:
    output = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
    ).stdout.decode("utf-8", errors="replace")
    return [path for path in output.split("\0") if path]


def load_allowlist() -> list[str]:
    path = os.path.join(REPO_ROOT, ALLOWLIST)
    if not os.path.exists(path):
        return []
    entries = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line and not line.startswith("#"):
                entries.append(line)
    return entries


def is_allowed(rel_path: str, allowlist: list[str]) -> bool:
    return any(fnmatch.fnmatch(rel_path, pattern) for pattern in allowlist)


def main() -> int:
    allowlist = load_allowlist()
    findings: list[str] = []

    for rel_path in tracked_files():
        if rel_path in PROHIBITED_PATHS or rel_path.endswith(PROHIBITED_SUFFIXES):
            findings.append(f"{rel_path}: prohibited file type must never be committed")
            continue
        if "/build/" in rel_path or rel_path.startswith("build/"):
            findings.append(f"{rel_path}: build output must not be committed")
            continue

        absolute = os.path.join(REPO_ROOT, rel_path)
        if not os.path.isfile(absolute):
            continue
        size = os.path.getsize(absolute)
        if size > MAX_TRACKED_BYTES:
            findings.append(f"{rel_path}: tracked file is {size} bytes (limit {MAX_TRACKED_BYTES})")
            continue
        if rel_path.endswith(SKIP_SUFFIXES):
            continue
        if is_allowed(rel_path, allowlist):
            continue

        try:
            with open(absolute, encoding="utf-8") as handle:
                content = handle.read()
        except (UnicodeDecodeError, OSError):
            continue

        for name, pattern in PATTERNS:
            for match in pattern.finditer(content):
                line = content.count("\n", 0, match.start()) + 1
                findings.append(f"{rel_path}:{line}: looks like a {name}")
                break  # one finding per pattern per file keeps the report readable

    if findings:
        print("secret scan FAILED")
        for finding in sorted(set(findings)):
            print(f"  {finding}")
        print()
        print("If a finding is a non-secret test fixture, add the path to " + ALLOWLIST)
        return 1

    print(f"secret scan clean ({len(tracked_files())} tracked files inspected)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
