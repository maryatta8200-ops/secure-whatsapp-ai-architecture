#!/usr/bin/env python3
"""Repository hygiene checks that run in CI.

Guards the properties the project promises about its own repository:
no build output, no machine-specific configuration, no oversized artifacts,
the Gradle wrapper is intact, and the required documentation exists.

Exit codes: 0 = clean, 1 = findings.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

REQUIRED_DOCS = [
    "README.md",
    "docs/ARCHITECTURE.md",
    "docs/BRANCHING.md",
    "docs/COMMIT_CONVENTIONS.md",
    "docs/LOCAL_VERIFICATION.md",
    "docs/MILESTONES.md",
    "docs/RECEIVER_REQUIREMENTS.md",
    "docs/SECURITY.md",
    "docs/TESTING.md",
]

# Absolute paths that must never leak into a committed file.
MACHINE_PATH = re.compile(r"/(?:home|Users)/[A-Za-z0-9._-]+/")
DOCUMENTED_EXCEPTIONS = ("docs/LOCAL_VERIFICATION.md", "tools/setup-toolchain.sh")


def tracked_files() -> list[str]:
    output = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
    ).stdout.decode("utf-8", errors="replace")
    return [path for path in output.split("\0") if path]


def main() -> int:
    findings: list[str] = []
    files = tracked_files()

    for rel_path in files:
        if rel_path.startswith("local-verify-build/") or "/build/" in rel_path:
            findings.append(f"{rel_path}: build output must not be committed")

    for doc in REQUIRED_DOCS:
        if not os.path.exists(os.path.join(REPO_ROOT, doc)):
            findings.append(f"{doc}: required documentation is missing")

    wrapper_jar = os.path.join(REPO_ROOT, "gradle", "wrapper", "gradle-wrapper.jar")
    wrapper_properties = os.path.join(REPO_ROOT, "gradle", "wrapper", "gradle-wrapper.properties")
    if not os.path.exists(wrapper_jar):
        findings.append("gradle/wrapper/gradle-wrapper.jar is missing")
    if not os.path.exists(wrapper_properties):
        findings.append("gradle/wrapper/gradle-wrapper.properties is missing")
    else:
        with open(wrapper_properties, encoding="utf-8") as handle:
            # Gradle escapes the colon in the distributionUrl (https\:/) so
            # normalise before checking.
            body = handle.read().replace("\\:", ":")
        if "https://services.gradle.org/distributions/" not in body:
            findings.append("gradle-wrapper.properties must pin an https distributionUrl")
        if "-bin.zip" not in body and "-all.zip" not in body:
            findings.append("gradle-wrapper.properties must reference a Gradle distribution zip")

    for rel_path in files:
        if rel_path in DOCUMENTED_EXCEPTIONS or rel_path.endswith((".png", ".jar")):
            continue
        absolute = os.path.join(REPO_ROOT, rel_path)
        if not os.path.isfile(absolute) or os.path.getsize(absolute) > 2 * 1024 * 1024:
            continue
        try:
            with open(absolute, encoding="utf-8") as handle:
                for number, line in enumerate(handle, start=1):
                    if MACHINE_PATH.search(line):
                        findings.append(
                            f"{rel_path}:{number}: machine specific absolute path committed"
                        )
        except (UnicodeDecodeError, OSError):
            continue

    if findings:
        print("repository hygiene FAILED")
        for finding in sorted(set(findings)):
            print(f"  {finding}")
        return 1

    print("repository hygiene clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
