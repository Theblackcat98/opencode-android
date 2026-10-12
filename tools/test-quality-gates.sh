#!/usr/bin/env bash
# Prove both advertised quality tasks fail on representative source violations.
# The temporary Kotlin file is removed even when a Gradle invocation or assertion fails.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PROBE="$ROOT/core/model/src/main/kotlin/dev/opencode/android/core/model/QualityGateProbe.kt"

if [[ -e "$PROBE" ]]; then
  printf 'refusing to overwrite existing probe path: %s\n' "$PROBE" >&2
  exit 1
fi
mkdir -p "$(dirname "$PROBE")"
cleanup() { rm -f "$PROBE"; }
trap cleanup EXIT

cat >"$PROBE" <<'KOTLIN'
package dev.opencode.android.core.model

fun detektQualityGateProbe(value: Int) = value+73
KOTLIN

expect_failure() {
  local task="$1"
  local output_name="$2"
  local rule_name="${3:-}"
  local output
  output="$(mktemp)"

  if ./gradlew "$task" --no-daemon --console=plain >"$output" 2>&1; then
    printf '%s unexpectedly passed with QualityGateProbe.kt present\n' "$task" >&2
    cat "$output" >&2
    rm -f "$output"
    exit 1
  fi
  if ! grep -Fq "$output_name" "$output"; then
    printf '%s failed, but did not report %s:\n' "$task" "$output_name" >&2
    cat "$output" >&2
    rm -f "$output"
    exit 1
  fi
  if [[ -n "$rule_name" ]] && ! grep -Fq "$rule_name" "$output"; then
    printf '%s failed, but did not report Detekt rule %s:\n' "$task" "$rule_name" >&2
    cat "$output" >&2
    rm -f "$output"
    exit 1
  fi
  rm -f "$output"
}

expect_failure ':core:model:spotlessKotlinCheck' 'QualityGateProbe.kt'
expect_failure ':core:model:detekt' 'QualityGateProbe.kt' 'MagicNumber'
printf 'Quality gate probe passed: Spotless and Detekt reject their representative violations.\n'
