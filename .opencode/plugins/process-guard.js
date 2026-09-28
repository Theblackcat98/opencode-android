/**
 * Blocks process-signalling commands issued through the bash tool.
 *
 * An agent runs inside a process tree whose ancestors include the opencode CLI itself, so a
 * pattern kill such as `pkill -f opencode`, or `ps aux | grep opencode | xargs kill`, terminates
 * the agent and its session. Bash permission globs cannot prevent this: they match the start of
 * the command, so an indirect call (`sh -c "kill ..."`, `... | xargs kill`, `$(kill ...)`) does
 * not look like `kill ...` to them.
 *
 * This hook inspects the entire command string instead, so it also catches signals hidden inside a
 * subshell, a pipe or a command substitution.
 *
 * Process lifecycle belongs to the test harness. See scripts/dev-server.sh.
 */

/**
 * A signal word anywhere in the command, including after a quote, so `sh -c "kill ..."` is caught.
 * The trailing guard keeps filenames such as `kill.sh` or `kill-switch` from tripping it.
 */
const SIGNALS_PROCESS = /(^|[^\w.-])(pkill|killall|kill)(?![\w.-])/

/**
 * Language-level equivalents, which the pattern above deliberately skips so that filenames like
 * `kill.sh` do not trip it: `process.kill(1)` in Node, `os.kill(pid, 9)` in Python.
 */
const SIGNALS_API = /(^|[^\w.-])(process|os|child_process\w*)\.kill\s*\(|\bkill\s*\(/

/**
 * Commands that may freely contain the word "kill" in prose, e.g. a commit message.
 * Fully anchored and metacharacter-free, so `ls; kill 1` is NOT excused.
 */
const MENTION_ONLY = [
  /^\s*(echo|printf|cat|head|tail|rg|grep|ls|find|stat|file|wc)\b[^;&|`$]*$/,
  /^\s*git\s+(commit|log)\b[^;&|`$]*$/,
]

/**
 * The one legitimate way to stop a server. Leading env assignments are allowed; chaining is not,
 * so the script cannot be used as a smuggling vector for a kill.
 */
const LIFECYCLE_SCRIPT =
  /^\s*(?:[A-Za-z_][A-Za-z0-9_]*=\S*\s+)*(\.\/)?scripts\/dev-server\.sh\b[^;&|`$]*$/

export const ProcessGuardPlugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      if (input.tool !== "bash") return

      const command = output?.args?.command
      if (typeof command !== "string" || command.length === 0) return

      if (LIFECYCLE_SCRIPT.test(command)) return
      if (MENTION_ONLY.some((pattern) => pattern.test(command))) return
      if (!SIGNALS_PROCESS.test(command) && !SIGNALS_API.test(command)) return

      throw new Error(
        [
          "Blocked by ProcessGuard: this command signals processes by name or by PID.",
          "",
          "Agents run inside a process tree that includes the opencode CLI, so a broad kill",
          "terminates the agent and its session. Do not work around this with sh -c, xargs, a",
          "subshell, or a script.",
          "",
          "Use instead:",
          "  scripts/dev-server.sh start|stop|status   to control the dev server",
          "  ./gradlew --stop                          to stop Gradle daemons",
          "",
          `Command was: ${command}`,
        ].join("\n"),
      )
    },
  }
}
