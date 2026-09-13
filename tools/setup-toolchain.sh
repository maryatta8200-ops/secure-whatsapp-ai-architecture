#!/usr/bin/env bash
# Installs the JDK and the Kotlin compiler used to verify the dependency-free
# `:core` module inside the sandbox.
#
# Why this exists: the development sandbox cannot reach Maven Central, Google
# Maven or services.gradle.org, so Gradle, the Android Gradle Plugin and the
# Android SDK cannot be fetched. A JDK plus kotlinc *can* be fetched (PyPI and
# npm are reachable), which is enough to compile and run every deterministic
# domain test locally. The Android build itself is verified by CI; see
# docs/MILESTONES.md and docs/LOCAL_VERIFICATION.md.
#
# This script installs nothing into the repository. Everything lands in
# $TOOLCHAIN_DIR (default: ~/.local/toolchain), which is git-ignored.
set -euo pipefail

TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.local/toolchain}"
JDK4PY_VERSION="${JDK4PY_VERSION:-21.0.8.2}"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.4.20}"

if [ -x "$TOOLCHAIN_DIR/jdk21/bin/java" ] && [ -x "$TOOLCHAIN_DIR/kotlinc/bin/kotlinc" ]; then
  echo "toolchain already present in $TOOLCHAIN_DIR"
  exit 0
fi

mkdir -p "$TOOLCHAIN_DIR"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "==> installing JDK $JDK4PY_VERSION"
python3 -m venv "$TOOLCHAIN_DIR/venv"
"$TOOLCHAIN_DIR/venv/bin/pip" install --quiet "jdk4py==$JDK4PY_VERSION"
JDK_ROOT="$("$TOOLCHAIN_DIR/venv/bin/python" -c 'import jdk4py, os; print(os.path.join(os.path.dirname(jdk4py.__file__), "java-runtime"))')"
ln -sfn "$JDK_ROOT" "$TOOLCHAIN_DIR/jdk21"

echo "==> installing Kotlin compiler $KOTLIN_VERSION"
npm pack --silent --pack-destination "$WORK_DIR" "kotlin-compiler@$KOTLIN_VERSION" >/dev/null
mkdir -p "$TOOLCHAIN_DIR/kotlinc"
tar xzf "$WORK_DIR/kotlin-compiler-$KOTLIN_VERSION.tgz" -C "$TOOLCHAIN_DIR/kotlinc" --strip-components=1

"$TOOLCHAIN_DIR/jdk21/bin/java" -version
"$TOOLCHAIN_DIR/kotlinc/bin/kotlinc" -version 2>&1 | head -1
echo "==> toolchain ready in $TOOLCHAIN_DIR"
