#!/usr/bin/env bash
# Compiles and runs the :core module's real unit tests without Gradle.
#
# The test sources are the exact files CI compiles against JUnit 4. Here they
# are compiled against the minimal local shim in tools/local-verify/stubs and
# executed by tools/local-verify/runner, because the sandbox cannot download the
# JUnit artifact. Nothing is stubbed out of the code under test.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.local/toolchain}"
KOTLINC="$TOOLCHAIN_DIR/kotlinc/bin/kotlinc"
JAVA="$TOOLCHAIN_DIR/jdk21/bin/java"

if [ ! -x "$KOTLINC" ] || [ ! -x "$JAVA" ]; then
  echo "error: toolchain not found in $TOOLCHAIN_DIR" >&2
  echo "run tools/setup-toolchain.sh first" >&2
  exit 2
fi

export JAVA_HOME="$TOOLCHAIN_DIR/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"

BUILD_DIR="$REPO_ROOT/local-verify-build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes"

echo "==> compiling :core (main + tests) with kotlinc"
find core/src/main/kotlin core/src/test/kotlin \
     tools/local-verify/stubs tools/local-verify/runner \
     -name '*.kt' -type f -print0 \
  | xargs -0 "$KOTLINC" -nowarn -jvm-target 17 -d "$BUILD_DIR/classes"

echo "==> running tests"
set +e
"$JAVA" -cp "$BUILD_DIR/classes:$TOOLCHAIN_DIR/kotlinc/lib/kotlin-stdlib.jar" \
  LocalTestRunnerKt "$BUILD_DIR/classes" "$@"
EXIT_CODE=$?
set -e
echo "==> local verification exit code: $EXIT_CODE"
exit $EXIT_CODE
