#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream, readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const rootArg = process.argv.indexOf("--root");
const root = rootArg === -1
  ? resolve(dirname(fileURLToPath(import.meta.url)), "..")
  : resolve(process.argv[rootArg + 1] ?? "");
const cliDir = join(root, "tools", "opencode-cli");
const manifest = JSON.parse(readFileSync(join(cliDir, "package.json"), "utf8"));
const lock = JSON.parse(readFileSync(join(cliDir, "package-lock.json"), "utf8"));
const checksums = JSON.parse(readFileSync(join(cliDir, "checksums.json"), "utf8"));
const version = manifest.dependencies?.["@opencode/cli"];
const errors = [];

async function sha256File(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

if (!version || !/^\d+\.\d+\.\d+$/.test(version)) {
  errors.push("package.json must pin @opencode/cli to an exact semver version");
}
if (process.env.OPENCODE_CLI_VERSION && process.env.OPENCODE_CLI_VERSION !== version) {
  errors.push(`OPENCODE_CLI_VERSION=${process.env.OPENCODE_CLI_VERSION} does not match the locked ${version}`);
}
const direct = lock.packages?.["node_modules/@opencode/cli"];
if (lock.lockfileVersion !== 3) errors.push("package-lock.json must use lockfileVersion 3");
if (direct?.version !== version) errors.push(`lockfile CLI version ${direct?.version ?? "missing"} does not match ${version}`);
if (!direct?.integrity || direct.integrity !== checksums.npmIntegrity) {
  errors.push("the direct CLI package integrity is missing or differs from checksums.json");
}

for (const [name, pkg] of Object.entries(lock.packages ?? {})) {
  if (name && pkg.resolved && !pkg.integrity) errors.push(`${name} has a resolved tarball but no integrity value`);
  if (name.startsWith("node_modules/@opencode/cli-") && pkg.version !== version) {
    errors.push(`${name} version ${pkg.version ?? "missing"} does not match ${version}`);
  }
}
for (const [name, pinnedVersion] of Object.entries(direct?.optionalDependencies ?? {})) {
  const pkg = lock.packages?.[`node_modules/${name}`];
  if (!pkg?.integrity || pkg.version !== pinnedVersion) {
    errors.push(`${name} is not locked with its exact version and integrity`);
  }
}

const workflowDir = join(root, ".github", "workflows");
for (const file of readdirSync(workflowDir).filter((name) => name.endsWith(".yml") || name.endsWith(".yaml"))) {
  const contents = readFileSync(join(workflowDir, file), "utf8");
  for (const [index, line] of contents.split(/\r?\n/).entries()) {
    const match = line.match(/^\s*uses:\s*([^\s#]+)/);
    if (!match || match[1].startsWith("./")) continue;
    if (!/^.+@[0-9a-f]{40}$/i.test(match[1])) {
      errors.push(`${file}:${index + 1} action reference is not pinned to a full commit SHA: ${match[1]}`);
    }
  }
}

const binaryArg = process.argv.indexOf("--binary");
if (binaryArg !== -1) {
  const binary = process.argv[binaryArg + 1];
  if (!binary) {
    errors.push("--binary requires an executable path");
  } else {
    const packageName = dirname(dirname(resolve(binary))).split(/[\\/]/).at(-1);
    const expected = checksums.linuxX64Binaries?.[packageName];
    if (!expected) {
      errors.push(`no recorded Linux x64 checksum for ${packageName}`);
    } else {
      try {
        const actual = await sha256File(binary);
        if (actual !== expected) errors.push(`${packageName} binary SHA-256 mismatch: expected ${expected}, got ${actual}`);
      } catch (error) {
        errors.push(`cannot read binary ${binary}: ${error.message}`);
      }
    }
  }
}

if (errors.length) {
  for (const error of errors) console.error(`integrity: ${error}`);
  process.exit(1);
}

console.log("CLI lockfile, npm integrities, platform checksums, and workflow action pins are valid.");
