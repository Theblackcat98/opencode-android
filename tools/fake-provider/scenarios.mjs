/**
 * Scripted responses for the fake provider.
 *
 * A scenario is a list of steps. The step a request gets is the number of assistant messages
 * already in the conversation, so a multi-turn scenario replays deterministically without any
 * server-side state: request 1 is step 0, the request after the first tool result is step 1.
 *
 * The scenario is chosen by the requested model id (`model` in the request body), so a whole
 * scenario runs by pointing one session at one model.
 *
 * A step is one of:
 *   { text: ["chunk", ...] }                          text deltas
 *   { reasoning: ["chunk", ...] }                     reasoning deltas
 *   { tool: { id, name, args } }                       one tool call, streamed as arguments
 *   { finish: "tool-calls" }                           finish reason override
 *   { error: { status, message, type } }               an HTTP error instead of a completion
 */

/** @type {Record<string, { description: string, steps: Step[] }>} */
export const SCENARIOS = {
  probe: {
    description: "Records the tools and the system prompt opencode offers, then answers with text.",
    steps: [{ text: ["probe acknowledged"] }],
  },
  text: {
    description: "A plain answer, streamed in several chunks.",
    steps: [{ text: ["The fake provider ", "streams plain text ", "in three chunks."] }],
  },
  reasoning: {
    description: "Reasoning deltas followed by an answer.",
    steps: [
      {
        reasoning: ["The user wants a deterministic answer. ", "No provider call is needed."],
        text: ["Reasoned, then answered."],
      },
    ],
  },
  shell: {
    description: "A shell tool call, then an answer quoting its output.",
    steps: [
      {
        tool: {
          id: "call_shell_1",
          name: "shell",
          args: { command: "echo fake-provider-hello", description: "Print a marker" },
        },
      },
      { text: ["The shell printed the marker."] },
    ],
  },
  edit: {
    description: "An edit tool call that rewrites a file, then an answer.",
    steps: [
      {
        tool: {
          id: "call_edit_1",
          name: "edit",
          args: {
            filePath: "fake-provider-target.txt",
            oldString: "before",
            newString: "after",
          },
        },
      },
      { text: ["Edited fake-provider-target.txt."] },
    ],
  },
  question: {
    description: "A question tool call, which the server turns into a form.",
    steps: [
      {
        tool: {
          id: "call_question_1",
          name: "question",
          args: {
            questions: [
              {
                question: "Which fake shell should run?",
                header: "Shell",
                options: [
                  { label: "bash", description: "Run bash" },
                  { label: "sh", description: "Run sh" },
                ],
              },
            ],
          },
        },
      },
      { text: ["Answered the question."] },
    ],
  },
  subagent: {
    description: "A subagent tool call, then an answer.",
    steps: [
      {
        tool: {
          id: "call_subagent_1",
          name: "subagent",
          args: {
            description: "Ask the fake subagent",
            prompt: "Reply with the word pong.",
            subagent_type: "general",
          },
        },
      },
      { text: ["The subagent answered."] },
    ],
  },
  error: {
    description: "One provider failure (which the server retries and records), then an answer.",
    steps: [
      { error: { status: 500, type: "server_error", message: "fake provider: scripted failure" } },
      { text: ["Recovered after the scripted failure."] },
    ],
  },
  long: {
    description: "A long answer used for screenshots and scrolling tests.",
    steps: [
      {
        text: [
          "## Markdown heading\n\n",
          "A paragraph with a list:\n\n",
          "- one\n- two\n- three\n\n",
          "A table:\n\n",
          "| a | b |\n| - | - |\n| 1 | 2 |\n\n",
          "Inline `code`, a [link](https://opencode.ai) and a fenced block:\n\n",
          "```kotlin\nval answer = 42\n```\n",
        ],
      },
    ],
  },
}

/** The model ids the provider advertises, one per scenario. */
export const MODEL_IDS = Object.keys(SCENARIOS)

/** The id of the step a conversation is on, derived from the messages already in it. */
export function stepIndex(messages) {
  if (!Array.isArray(messages)) return 0
  return messages.filter((message) => message && message.role === "assistant").length
}

export function scenarioFor(model) {
  const id = String(model ?? "").split("/").pop()
  return SCENARIOS[id] ? id : "text"
}
