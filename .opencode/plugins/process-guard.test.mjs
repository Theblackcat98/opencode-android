import { ProcessGuardPlugin } from "./process-guard.js"

const hooks = await ProcessGuardPlugin({})
const before = hooks["tool.execute.before"]

const verdict = async (command) => {
  try {
    await before({ tool: "bash" }, { args: { command } })
    return "allow"
  } catch {
    return "BLOCK"
  }
}

const cases = [
  // the exact command that killed the session
  ['kill -TERM 1390373 2472236 2>/dev/null; sleep 3; ps aux | grep "[o]pencode" | awk \'{print $2}\' | tr \'\\n\' \' \'', true],
  // direct signals
  ["kill -9 4242", true],
  ["pkill -f opencode", true],
  ["killall node", true],
  ["kill %1", true],
  ["kill $SERVER_PID", true],
  // indirect, which permission globs cannot see
  ['ps aux | grep "[o]pencode" | awk \'{print $2}\' | xargs kill', true],
  ['bash -c "kill -9 1234"', true],
  ["sh -c 'kill $PID'", true],
  ['sh -c "kill -9 $(pgrep opencode)"', true],
  ['node -e "process.kill(1)"', true],
  ['python3 -c "import os; os.kill(1, 9)"', true],
  ['cat pids.txt | xargs kill -9', true],
  ["xargs kill < pids.txt", true],
  // chained behind something innocent
  ["echo hi; kill 1", true],
  ["ls; kill 1", true],
  ["nohup ./gradlew build & kill $!", true],
  // must be allowed
  ["./gradlew build", false],
  ["./gradlew --stop", false],
  ["scripts/dev-server.sh start", false],
  ["scripts/dev-server.sh stop", false],
  ["ANDROID_HOME=$HOME/Android/Sdk scripts/dev-server.sh start", false],
  ["timeout 30 ./scripts/dev-server.sh start && sleep 2", false],
  ['git commit -m "fix the kill switch"', false],
  ['echo "do not kill the server by pattern"', false],
  ["rg -n 'kill' scripts/", false],
  ['ps aux | grep "[o]pencode serve"', false],
  ["kill.sh ./stop", false],
  ["./scripts/kill-server.sh", false],
  ["ANDROID_SDK=1 scripts/setup-android-sdk.sh", false],
  ["npm run check-api-drift", false],
  ["node tools/fake-provider/server.mjs", false],
]

let bad = 0
for (const [cmd, wantBlock] of cases) {
  const got = await verdict(cmd)
  const ok = (got === "BLOCK") === wantBlock
  if (!ok) bad++
  console.log(`${ok ? "PASS" : "FAIL"}  ${got.padEnd(5)}  ${cmd.slice(0, 78)}`)
}

console.log(`\n${cases.length - bad}/${cases.length} as expected`)
process.exit(bad ? 1 : 0)
