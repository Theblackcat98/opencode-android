#!/usr/bin/env bash
# Safe smoke test: runs the bootstrap script against a fake sdkmanager in a temporary
# SDK/repository. No real SDK package is downloaded and no Android license is accepted.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

TEST_REPO="$TMP/repo"
SDK="$TMP/sdk"
LOG="$TMP/sdkmanager.log"
mkdir -p "$TEST_REPO/scripts" "$SDK/cmdline-tools/latest/bin"
cp "$ROOT/scripts/setup-android-sdk.sh" "$TEST_REPO/scripts/setup-android-sdk.sh"
printf 'Pkg.Revision=20.0\n' >"$SDK/cmdline-tools/latest/source.properties"

cat >"$SDK/cmdline-tools/latest/bin/sdkmanager" <<'FAKE_SDKMANAGER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$SDKMANAGER_LOG"

if [[ "${2:-}" == "--licenses" ]]; then
  # Deliberately accept nothing; the bootstrap is being checked with a fake manager.
  exit 0
fi

if [[ "${2:-}" != "--install" ]]; then
  printf 'unexpected sdkmanager invocation: %s\n' "$*" >&2
  exit 64
fi
shift 2
for package in "$@"; do
  directory="${package//;/\/}"
  mkdir -p "$SDKMANAGER_SDK/$directory"
  printf '<LocalPackage path="%s"/>\n' "$package" >"$SDKMANAGER_SDK/$directory/package.xml"
done
FAKE_SDKMANAGER
chmod +x "$SDK/cmdline-tools/latest/bin/sdkmanager"

export SDKMANAGER_LOG="$LOG"
export SDKMANAGER_SDK="$SDK"
env -u ANDROID_PLATFORM -u ANDROID_BUILD_TOOLS -u ANDROID_EXTRA_PACKAGES \
  ANDROID_HOME="$SDK" \
  bash "$TEST_REPO/scripts/setup-android-sdk.sh" >"$TMP/exports" 2>"$TMP/bootstrap.log"

expected='--install platform-tools platforms;android-37 build-tools;36.0.0'
grep -F -- "$expected" "$LOG" >/dev/null || {
  printf 'expected sdkmanager to request: %s\nactual calls:\n' "$expected" >&2
  cat "$LOG" >&2
  exit 1
}
if grep -F -- 'platforms;android-37.0' "$LOG" >/dev/null; then
  printf 'invalid android-37.0 package was requested\n' >&2
  exit 1
fi
[[ -f "$SDK/platforms/android-37/package.xml" ]]
[[ ! -e "$SDK/platforms/android-37.0" ]]
grep -Fxq "sdk.dir=$SDK" "$TEST_REPO/local.properties"
printf 'Android SDK bootstrap smoke test passed (requested platforms;android-37).\n'
