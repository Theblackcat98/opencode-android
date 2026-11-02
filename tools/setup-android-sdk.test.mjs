#!/usr/bin/env node
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, readFileSync, chmodSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const script = join(root, "scripts", "setup-android-sdk.sh");
const toml = join(root, "gradle", "libs.versions.toml");

const libsVersions = readFileSync(toml, "utf8");
const match = libsVersions.match(/^compileSdk\s*=\s*"(\d+)"/m);
const compileSdk = match ? match[1] : "36";

const fixture = mkdtempSync(join(tmpdir(), "setup-android-sdk-test-"));
try {
  mkdirSync(join(fixture, "cmdline-tools", "latest", "bin"), { recursive: true });
  writeFileSync(join(fixture, "cmdline-tools", "latest", "source.properties"), "Pkg.Revision=20.0\n");

  const sdkmanager = join(fixture, "cmdline-tools", "latest", "bin", "sdkmanager");
  writeFileSync(sdkmanager, `#!/usr/bin/env bash
if [[ "$*" == *"--install"* ]]; then
  for arg in "$@"; do
    if [[ "$arg" != "--"* ]]; then
      dir="$ANDROID_HOME/\${arg//;//}"
      mkdir -p "$dir"
      touch "$dir/package.xml"
    fi
  done
fi
echo "$*" >> "$ANDROID_HOME/sdkmanager.log"
`);
  chmodSync(sdkmanager, 0o755);

  const result = spawnSync(script, [], { env: { ...process.env, ANDROID_HOME: fixture, ANDROID_SDK_ROOT: fixture }, encoding: "utf8" });
  if (result.status !== 0) {
     console.error("Script failed:");
     console.error(result.stderr);
     process.exit(1);
  }

  const log = readFileSync(join(fixture, "sdkmanager.log"), "utf8");
  assert.match(log, new RegExp(`platforms;android-${compileSdk}(?!\\.0)`), "sdkmanager should be called with exactly platforms;android-XX, not android-XX.0");
  assert.doesNotMatch(log, /android-\d+\.0/, "sdkmanager should never be called with a .0 platform version");

  console.log("clean-bootstrap smoke test passed.");
} finally {
  rmSync(fixture, { recursive: true, force: true });
}
