#!/usr/bin/env node

import assert from "node:assert/strict";
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const verifier = join(repo, "tools", "verify-opencode-cli.mjs");
const fixture = mkdtempSync(join(tmpdir(), "opencode-cli-integrity-test-"));
const copy = (source, destination) => {
  mkdirSync(dirname(destination), { recursive: true });
  copyFileSync(source, destination);
};

try {
  for (const file of ["package.json", "package-lock.json", "checksums.json"]) {
    copy(join(repo, "tools", "opencode-cli", file), join(fixture, "tools", "opencode-cli", file));
  }
  const sourceWorkflows = join(repo, ".github", "workflows");
  for (const file of readdirSync(sourceWorkflows).filter((name) => /\.ya?ml$/.test(name))) {
    copy(join(sourceWorkflows, file), join(fixture, ".github", "workflows", file));
  }

  const run = (...args) => spawnSync(process.execPath, [verifier, "--root", fixture, ...args], { encoding: "utf8" });
  const good = run();
  assert.equal(good.status, 0, good.stderr || good.stdout);

  const lockPath = join(fixture, "tools", "opencode-cli", "package-lock.json");
  const originalLock = readFileSync(lockPath, "utf8");
  const tamperedLock = JSON.parse(originalLock);
  tamperedLock.packages["node_modules/@opencode/cli"].integrity = "sha512-tampered";
  writeFileSync(lockPath, JSON.stringify(tamperedLock));
  const lockFailure = run();
  assert.notEqual(lockFailure.status, 0, "a modified CLI integrity must fail verification");
  assert.match(lockFailure.stderr, /integrity/i);
  writeFileSync(lockPath, originalLock);

  const workflowPath = join(fixture, ".github", "workflows", "ci.yml");
  const originalWorkflow = readFileSync(workflowPath, "utf8");
  const mutableWorkflow = originalWorkflow.replace(/(actions\/checkout)@[0-9a-f]{40}/i, "$1@v4");
  assert.notEqual(mutableWorkflow, originalWorkflow, "the fixture must contain a pinned checkout action");
  writeFileSync(workflowPath, mutableWorkflow);
  const actionFailure = run();
  assert.notEqual(actionFailure.status, 0, "a mutable GitHub Actions ref must fail verification");
  assert.match(actionFailure.stderr, /not pinned/i);
  writeFileSync(workflowPath, originalWorkflow);

  const binary = join(fixture, "tools", "opencode-cli", "node_modules", "@opencode", "cli-linux-x64", "bin", "opencode");
  mkdirSync(dirname(binary), { recursive: true });
  writeFileSync(binary, "tampered binary");
  const binaryFailure = run("--binary", binary);
  assert.notEqual(binaryFailure.status, 0, "a binary with the wrong checksum must fail verification");
  assert.match(binaryFailure.stderr, /SHA-256 mismatch/i);

  console.log("Integrity verifier correctly rejects tampered package, action, and binary inputs.");
} finally {
  rmSync(fixture, { recursive: true, force: true });
}
