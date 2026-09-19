#!/usr/bin/env bash
#
# Compiles the real app sources for the Android-free layers (data / domain /
# pipeline) together with the JVM verification harness, then runs the harness
# against the fixtures in app/src/test/resources/fixtures (shared with the
# Gradle unit tests).
#
# This is a convenience for working without Gradle / the Android SDK. The
# equivalent Gradle command is:  ./gradlew :app:testDebugUnitTest
#
# Requirements: a JDK on PATH and kotlinc. Point KOTLINC_HOME at a Kotlin
# compiler distribution if kotlinc is not on PATH, e.g.
#     KOTLINC_HOME=~/kotlin-compiler verification/run_tests.sh
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

APP_SRC="app/src/main/java/com/eduvpn/onetap"

# Only the Android-free layers are compiled here. data/remote (OkHttp), vpn/
# (the OpenVPN engine) and the Activity need the Android SDK and are exercised
# by the Gradle build instead.
PURE_DIRS=(
  "$APP_SRC/data/model"
  "$APP_SRC/data/parser"
  "$APP_SRC/domain"
  "$APP_SRC/pipeline"
)
for dir in "${PURE_DIRS[@]}"; do
  [ -d "$dir" ] || { echo "error: missing source dir $dir" >&2; exit 1; }
done

# Guard the boundary: if anyone adds an Android or OkHttp import to one of these
# packages, the harness can no longer prove the parsing/selection logic on a
# plain JVM, and that is the point of this script. Fail loudly instead of
# silently narrowing what is tested.
FORBIDDEN_IMPORT='^import +(android\.|androidx\.|okhttp3\.|kotlinx\.coroutines\.)'
if grep -RnE "$FORBIDDEN_IMPORT" "${PURE_DIRS[@]}"; then
  echo "error: an Android/framework-only import leaked into a JVM-tested package" >&2
  exit 1
fi

if [ -n "${KOTLINC_HOME:-}" ] && [ -x "$KOTLINC_HOME/bin/kotlinc" ]; then
  KOTLINC="$KOTLINC_HOME/bin/kotlinc"
elif command -v kotlinc >/dev/null 2>&1; then
  KOTLINC="$(command -v kotlinc)"
else
  echo "error: kotlinc not found. Set KOTLINC_HOME to a Kotlin compiler distribution." >&2
  exit 127
fi

OUT="verification/build"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "kotlinc: $KOTLINC"
echo "compiling Android-free sources + harness ..."
# shellcheck disable=SC2046
"$KOTLINC" \
  -nowarn \
  -d "$OUT/classes" \
  $(find "${PURE_DIRS[@]}" -name '*.kt' | sort) \
  verification/src/VerificationMain.kt

echo
echo "running harness ..."
# kotlin-stdlib is not on the JVM classpath by default; pick it up from the
# compiler distribution the same way kotlinc does.
KOTLIN_LIB="$(dirname "$(dirname "$KOTLINC")")/lib"
CP="$OUT/classes"
for jar in "$KOTLIN_LIB"/kotlin-stdlib.jar "$KOTLIN_LIB"/annotations-13.0.jar; do
  [ -f "$jar" ] && CP="$CP:$jar"
done
EDUVPN_FIXTURES="${EDUVPN_FIXTURES:-app/src/test/resources/fixtures}" \
EDUVPN_FULL_SNAPSHOT="${EDUVPN_FULL_SNAPSHOT:-}" \
  java -cp "$CP" com.eduvpn.onetap.verification.VerificationMainKt
