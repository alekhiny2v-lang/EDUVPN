#!/usr/bin/env bash
#
# Type-checks the Android-coupled app sources (MainActivity, OneTapViewModel,
# the OpenVPN controllers, the OkHttp fetcher) WITHOUT an Android SDK.
#
# How it works, and what it does and does not prove:
#
#   * The Java stubs under android-stubs/java stand in for android.*, androidx.*,
#     okhttp3.* and de.blinkt.openvpn.*. kotlinc reads Java sources for
#     resolution, so no javac is needed.
#   * The ics-openvpn signatures in those stubs are transcribed verbatim from
#     ics-openvpn v0.7.65, so a wrong argument list, a renamed method or a bad
#     nullability choice in THIS project will be caught.
#   * kotlinx-coroutines is the real artifact, taken from the Kotlin compiler
#     distribution, so coroutine and Flow usage is checked for real.
#   * androidx.* and okhttp3.* are approximated. A signature drift there would
#     slip through; the Gradle build remains the authority for those.
#   * Nothing is executed - every stub body throws or returns a default.
#
# Usage:
#   KOTLINC_HOME=~/kotlin-compiler verification/typecheck_android_sources.sh
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ -n "${KOTLINC_HOME:-}" ] && [ -x "$KOTLINC_HOME/bin/kotlinc" ]; then
  KOTLINC="$KOTLINC_HOME/bin/kotlinc"
  KOTLIN_LIB="$KOTLINC_HOME/lib"
elif command -v kotlinc >/dev/null 2>&1; then
  KOTLINC="$(command -v kotlinc)"
  KOTLIN_LIB="$(dirname "$(dirname "$KOTLINC")")/lib"
else
  echo "error: kotlinc not found. Set KOTLINC_HOME." >&2
  exit 127
fi

COROUTINES_JAR="$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar"
if [ ! -f "$COROUTINES_JAR" ]; then
  echo "error: $COROUTINES_JAR missing - this script needs the real coroutines artifact" >&2
  exit 1
fi

OUT="verification/build/typecheck"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "kotlinc: type-checking the real app sources against the API stubs ..."
# shellcheck disable=SC2046
"$KOTLINC" \
  -nowarn \
  -cp "$COROUTINES_JAR" \
  -d "$OUT/classes" \
  $(find app/src/main/java -name '*.kt' | sort) \
  $(find verification/android-stubs/kotlin -name '*.kt' | sort) \
  $(find verification/android-stubs/java -name '*.java' | sort)

echo "OK: every Kotlin source in app/src/main/java type-checks."

if [ -d alternative/aidl-integration ]; then
  echo
  echo "kotlinc: type-checking the alternative AIDL controller ..."
  # shellcheck disable=SC2046
  "$KOTLINC" \
    -nowarn \
    -cp "$COROUTINES_JAR" \
    -d "$OUT/alt-classes" \
    $(find app/src/main/java -name '*.kt' | sort) \
    $(find alternative/aidl-integration -name '*.kt' | sort) \
    $(find verification/android-stubs/kotlin -name '*.kt' | sort) \
    $(find verification/android-stubs/java -name '*.java' | sort)
  echo "OK: alternative/aidl-integration type-checks."
fi
