#!/usr/bin/env bash
# Installs the Android SDK command-line tools and the packages this project needs.
#
# Idempotent: safe to run repeatedly. Packages already present are skipped, and the
# command-line tools archive is verified by SHA-1 before it is unpacked.
#
# Usage:
#   scripts/setup-android-sdk.sh            # installs into $ANDROID_HOME, or ~/android-sdk
#   ANDROID_HOME=/opt/android-sdk scripts/setup-android-sdk.sh
#
# After it runs, it prints the ANDROID_HOME to export and writes local.properties
# (sdk.dir=...) in the repository root when that file does not exist yet.
#
# Keep BUILD_TOOLS in sync with the Android Gradle Plugin's default build-tools version.
set -euo pipefail

# Pin command-line tools 23.0: its sdkmanager compatibility shim sees the current SDK
# repository metadata used for API 37. The shim downloads Android CLI, so give it an
# isolated HOME with metrics disabled (via the documented .androidrc option).
CMDLINE_TOOLS_BUILD="16111833"
CMDLINE_TOOLS_VERSION="23.0"
CMDLINE_TOOLS_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-36.0.0}"
EXTRA_PACKAGES="${ANDROID_EXTRA_PACKAGES:-}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPILE_SDK="${ANDROID_COMPILE_SDK:-$(sed -n 's/^compileSdk = "\([0-9][0-9]*\)"$/\1/p' "$REPO_ROOT/gradle/libs.versions.toml")}"
if [[ -z "$COMPILE_SDK" ]]; then
  printf '[setup-android-sdk] could not read compileSdk from gradle/libs.versions.toml\n' >&2
  exit 1
fi
PLATFORM="${ANDROID_PLATFORM:-android-${COMPILE_SDK}.0}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"

log() { printf '[setup-android-sdk] %s\n' "$*" >&2; }

retry() {
  local attempt=1 delay=2
  until "$@"; do
    if (( attempt >= 5 )); then
      log "command failed after $attempt attempts: $*"
      return 1
    fi
    log "attempt $attempt failed; retrying in ${delay}s: $*"
    sleep "$delay"
    attempt=$((attempt + 1))
    delay=$((delay * 2))
  done
}

need() {
  command -v "$1" >/dev/null 2>&1 || { log "missing required tool: $1"; exit 1; }
}

need curl
need unzip
need java

# Android CLI reads this per-user file; isolate it so the bootstrap neither enables metrics nor
# overwrites a developer's existing CLI preferences.
CLI_HOME="$(mktemp -d)"
printf '%s\n' '--no-metrics' >"$CLI_HOME/.androidrc"
tmp=""
cleanup() {
  if [[ -n "$tmp" ]]; then rm -rf "$tmp"; fi
  rm -rf "$CLI_HOME"
}
trap cleanup EXIT

mkdir -p "$SDK"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

installed_version() {
  sed -n 's/^Pkg.Revision=//p' "$SDK/cmdline-tools/latest/source.properties" 2>/dev/null || true
}

if [[ ! -x "$SDKMANAGER" || "$(installed_version)" != "$CMDLINE_TOOLS_VERSION" ]]; then
  log "installing command-line tools $CMDLINE_TOOLS_VERSION (build $CMDLINE_TOOLS_BUILD) into $SDK"
  tmp="$(mktemp -d)"
  zip="$tmp/cmdline-tools.zip"
  url="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  retry curl -fsSL --retry 3 -o "$zip" "$url"
  actual="$(sha1sum "$zip" | cut -d' ' -f1)"
  if [[ "$actual" != "$CMDLINE_TOOLS_SHA1" ]]; then
    log "checksum mismatch for $url: expected $CMDLINE_TOOLS_SHA1, got $actual"
    exit 1
  fi
  unzip -q "$zip" -d "$tmp/unpacked"
  rm -rf "$SDK/cmdline-tools/latest"
  mkdir -p "$SDK/cmdline-tools"
  mv "$tmp/unpacked/cmdline-tools" "$SDK/cmdline-tools/latest"
else
  log "command-line tools $CMDLINE_TOOLS_VERSION already present"
fi

packages=("platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS")
for extra in $EXTRA_PACKAGES; do packages+=("$extra"); done

missing=()
for pkg in "${packages[@]}"; do
  dir="$SDK/${pkg//;//}"
  if [[ -f "$dir/package.xml" ]]; then
    log "already installed: $pkg"
  else
    missing+=("$pkg")
  fi
done

# Accept licenses every time: cheap, and it keeps AGP's on-demand downloads working.
# `yes` exits with SIGPIPE once the manager stops reading, so ignore its status.
set +o pipefail
yes 2>/dev/null | env HOME="$CLI_HOME" "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null
set -o pipefail

if (( ${#missing[@]} > 0 )); then
  log "installing: ${missing[*]}"
  retry env HOME="$CLI_HOME" "$SDKMANAGER" --sdk_root="$SDK" --install "${missing[@]}" >/dev/null
fi

for pkg in "${packages[@]}"; do
  [[ -f "$SDK/${pkg//;//}/package.xml" ]] || { log "package missing after install: $pkg"; exit 1; }
done

if [[ ! -f "$REPO_ROOT/local.properties" ]]; then
  printf 'sdk.dir=%s\n' "$SDK" >"$REPO_ROOT/local.properties"
  log "wrote $REPO_ROOT/local.properties"
fi

# In GitHub Actions, expose the SDK to later steps.
if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "ANDROID_HOME=$SDK"
    echo "ANDROID_SDK_ROOT=$SDK"
  } >>"$GITHUB_ENV"
fi

log "Android SDK ready at $SDK"
echo "export ANDROID_HOME=$SDK"
