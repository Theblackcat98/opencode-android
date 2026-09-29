# Coverage audit

Plan §6's first Phase 10 exit criterion: **every row in §7 and §8 is implemented and tested.**

This is the record of checking that, and of what the check found. Three phases before this one each
claimed it from reading the plan, and each was wrong in a different way — the most recent was a §8
row marked Complete that no code handled at all. So the claim is derived from the code and asserted
by the build, not written down.

## How it is checked

| Tool | What it derives | Where it runs |
| --- | --- | --- |
| `tools/audit-coverage.mjs` | §7 and §8 from the plan, `ServerApi`, `EventTypes` and the dispatch points in `ServerDataSet.apply` | locally, and in CI with `--strict` |
| `CoverageMatrixTest` | the same rules, restated in Kotlin | `core:network` unit tests |
| `tools/gen-event-payloads.mjs --check` | that the committed event corpus matches the vendored declarations | CI |
| `EventPayloadCoverageTest` | that all 93 payloads decode, round-trip, and drop no field | `core:model` unit tests |

`CoverageMatrixTest` exists because a script nobody runs is a script that drifts. CI runs both.

## The result

```
§7 API coverage:   138 rows | declared 138 | wired 138 | tested 138
§8 Event coverage:  94 types | handled 94 | tested 94
```

**138 of 138 rows declared, wired and tested. 94 of 94 event types handled and tested.**

## The rules, and why each is the rule it is

A row is **declared** when `ServerApi` carries the exact method and path. **Wired** when production
code reaches it through the call chain — transitively, and across files, because a surface is
usually in a different module from the store it delegates to. **Tested** when a test names a
declaration somewhere on that chain, which is how a test that drives a wrapper is recognised.

An event is **handled** when one of the four dispatch points can send it: the `when` in
`ServerDataSet.apply` naming it, `RequestCenter` matching it (which `apply` also calls, for every
frame), or it implementing `EventPayload.SessionScoped`, which `apply` routes into the reducer. It is
**tested** when the generated corpus carries an instance of its type that `EventPayloadCoverageTest`
decodes.

Both rules are by mechanism, not by a hand-maintained list, so a new payload class is covered the
moment it is declared `SessionScoped` and reported as a gap the moment it is handled nowhere.

## What the matrices claimed and the code did not back

This is the part the criterion is about. Every row below was a row the plan listed as done.

### Operations with no production caller — 12

Declared on `ServerApi`, exercised only by the live integration tests, and reachable from no screen.

| Operation | Phase | What it now has |
| --- | --- | --- |
| `GET /api/session/{id}/inbox` | P2 | `SessionCommands.listInbox`, for the queue after a gap in the stream |
| `GET /api/session/{id}/message/{mid}` | P2 | `SessionCommands.getMessage`, for a deep link naming a message the timeline has not paged in |
| `GET /api/session/{id}/form` | P3 | `SessionCommands.listSessionForms`, for a session screen rather than the inbox |
| `GET /api/session/{id}/form/{fid}` | P3 | `SessionCommands.getSessionForm`, to answer "did my answer land?" from the server |
| `GET /api/session/{id}/permission` | P3 | `SessionCommands.listSessionPermissions`, same reason |
| `GET /api/session/{id}/permission/{rid}` | P3 | `SessionCommands.getSessionPermission`, read before offering a choice |
| `GET /api/agent/{id}` | P2 | `ServerDataSet.agent`, the agent's own record rather than a catalog row |
| `GET /api/provider/{id}` | P8 | `IntegrationSurface.provider` |
| `GET /api/integration/{id}` | P8 | `IntegrationSurface.integration` |
| `GET /api/pty/{id}` | P7 | `ExecutionCommands.pty`, re-read when a terminal is opened |
| `GET /api/pty/{id}/connect` | P7 | **left as it is, on purpose** — see below |
| `GET …/persistent-pty/{id}/connect` | P7 | **left as it is, on purpose** — see below |

The two WebSocket upgrades are the only rows not given a caller, and that is deliberate. A Retrofit
`suspend fun` cannot perform a WebSocket upgrade, and `PtySocket` builds the same URL as a socket
request and sends Basic auth on it, which is the path plan §2.11 documents for a native client. P7
left the declarations on `ServerApi` with a comment saying so. The audit now records them as stated
deviations pointing at `PtySocket` rather than as gaps, and `PtySocketTest` is what covers them.

### Operations called in production but reached by no test — 24

Every one of these was in the plan as delivered and in the plan as Complete. A declaration and a
caller are not a test: nothing asserted that the route was spelled right, that the body was the
documented shape, or that a failure was classified.

They are now covered by `CoverageGapWireTest`, which drives the production methods over real HTTP
rather than a mock of `ServerApi` — a mock would pass whether the route were correct or not. The
properties it asserts are the ones a mock cannot see:

- the per-attempt OAuth and command routes carry the integration **and** the attempt in the path,
  so cancelling one attempt cannot cancel another's;
- a credential rename sends **only** the label, because the patch body is closed and a key sent by
  accident would be stored;
- adding an MCP server invalidates the server list *and* the resource catalog;
- a terminal rename sends no size and a resize sends no title;
- the global config patch names the global file, because it writes the global document.

### Events modelled but handled nowhere — 5

`tui.toast.show`, `tui.prompt.append`, `tui.session.select`, `tui.command.execute` and the
`rpc.<id>.<event>` family had payload classes since P0 and no handler. They now go to `TuiControl`
through `ServerDataSet.apply`, and the behaviour is asserted: a toast becomes a snackbar at the
server's own variant, prompt text and session selection are ignored while follow-desktop is off, and a
command that would change server state is offered rather than performed.

### Event types with a binding and no payload any test had decoded — 46

The recorded stream in the fixtures is real server output, and it exercises 47 of the 93 named types.
The other 46 had a registry entry, a round-trip test that could not reach them, and nothing else.

`tools/gen-event-payloads.mjs` derives one minimal instance of each from the vendored TypeScript
declarations and the OpenAPI schemas, and `EventPayloadCoverageTest` decodes all of them, round-trips
them, and **asserts that no published `data` member is dropped**. That last check is the one that
matters: `ignoreUnknownKeys` means a model missing a field still decodes cleanly, so a dropped field
is invisible to every other test in the suite. Removing one field from `SessionRevertCommitted`
fails that test, which is how it was verified.

### Two bugs the audit's own tools found

Building the corpus decoded every type and found one crash: a union member arriving as something
other than a JSON object threw out of the decoder instead of falling back to `Unknown`, which would
have taken the event connection down over one malformed frame.

Building the statistics surface and testing it over HTTP found a second: `SessionStatsTools`
discriminates on `mode`, not on the `type` every other union in the package uses, so every answer
decoded as `Unknown` and the dashboard would have shown no tool data on a server that sent plenty.
Reverting that fix fails three tests.

## The five documented deviations

Rows the plan lists that `ServerApi` does not declare as an ordinary annotation. Each is a decision
with a reason, and the reason has to be re-argued if it goes.

| Row | Why |
| --- | --- |
| `GET /api/fs/read/*` | A wildcard path, which Retrofit has no syntax for. The tail is a `@Url`. |
| `DELETE /api/worktree` | A `DELETE` with a body, so it is declared with `@HTTP`. |
| `GET /api/event` | An SSE stream; `EventStreamClient` reads it over OkHttp directly. |
| `GET /api/pty/{id}/connect` | A WebSocket upgrade, which Retrofit cannot make. |
| `GET …/persistent-pty/{id}/connect` | The same, for a session terminal. |

## Reproducing it

```bash
node tools/audit-coverage.mjs --strict     # exits 1 on any gap
node tools/audit-coverage.mjs --json       # the whole table
./gradlew :core:network:test --tests '*CoverageMatrixTest'
node tools/gen-event-payloads.mjs --check  # the corpus is current
```

## Known limitations

- **The rules are syntactic.** "Wired" means a production declaration on the call chain is named
  somewhere in a test source, and "tested" means the name appears. It cannot tell whether the
  assertion is a good one. That is why the new tests assert wire properties rather than
  `assertTrue(result.isSuccess)`, and it is a real limit: a test that names a method and asserts
  nothing would satisfy this.
- **The corpus is derived from the vendored declarations**, so it proves the models match the
  *published* types. A server that sends something else is covered by the live integration tests,
  which need a real server and were not run in this environment.
- **No live server test was run.** The `Live*IntegrationTest` classes need a real `opencode serve`
  with a password and a fake provider; they skip without one. That is a genuine gap in confidence
  that no amount of unit testing closes.
