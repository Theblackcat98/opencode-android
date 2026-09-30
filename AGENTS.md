# AGENTS.md

Native Android client for [OpenCode](https://opencode.ai) **V2** (`@opencode/cli` 2.0.18). Kotlin, Compose, Hilt, Room, Retrofit/OkHttp. 16 Gradle modules — `:app`, six `:core:*`, nine `:feature:*` — plus the `build-logic` included build.

**The build is the source of truth, not the documents.** This repo asserts claims with gates rather than prose, and several documents are parsed by tooling. When docs and a script disagree, the script wins — and a document that is wrong is a bug to fix, not to work around.

## Verify in two tiers

**Fast gates** — Node, no Gradle, no network, ~1s each. Run these first and after every contract change:

```bash
node tools/audit-coverage.mjs --strict     # §7/§8 plan matrices vs the code
node tools/gen-event-payloads.mjs --check  # event corpus is current
node tools/check-api-drift.test.mjs        # drift tool's own tests
node tools/audit-viewmodels.test.mjs && node tools/audit-viewmodels.mjs  # every ViewModel is @HiltViewModel
node .opencode/plugins/process-guard.test.mjs
node tools/audit-strings.mjs              # UI strings externalized
node tools/audit-accessibility.mjs
```

`audit-viewmodels` exists because `hiltViewModel()` compiles for any `ViewModel` and fails only when the screen opens; seven screens crashed that way. Only `--strict`, `--check` and `audit-viewmodels` fail on their own. **`audit-strings` and `audit-accessibility` always exit 0** — read the output. CI gates strings through a shell pipeline over `audit-strings --json` in `.github/workflows/ci.yml`, not through the tool.

**Gradle gates** (this is what CI runs, in this order):

```bash
./gradlew unitTest        # the main gate; ~40s with a warm cache
./gradlew lintDebug
./gradlew assembleDebug   # play + fdroid
./gradlew spotlessCheck   # ktlint — the formatting gate
./gradlew verifyRoborazziDebug
```

**`./gradlew check` is currently red.** detekt is pinned to `2.0.0-alpha.6` (`gradle/libs.versions.toml`) but `config/detekt/detekt.yml` uses the 1.x schema — `build.maxIssues`, `complexity>LongMethod>threshold` and `style>UnusedPrivateMember` are all rejected, so every module's `detekt` task fails on config validation. CI never runs `check` (only `unitTest`/`lintDebug`/`assembleDebug`), which is why nobody noticed. Do not "fix" this by adding a detekt baseline; the file's own header says a baseline is how a zero-findings claim is faked.

**There is no `ktlintCheck` task.** The ktlint gate is Spotless: `spotlessCheck` / `spotlessKotlinCheck`. `QualityConventionPlugin`'s KDoc says otherwise and is wrong.

## Test task names — the biggest footgun

| Module kind | Concrete test task | Example |
| --- | --- | --- |
| Android library / feature | `testDebugUnitTest` | `./gradlew :feature:sessions:testDebugUnitTest` |
| `core:model` (plain JVM) | `test` | `./gradlew :core:model:test` |
| `app` (two flavors) | `testPlayDebugUnitTest` / `testFdroidDebugUnitTest` | `./gradlew :app:unitTest` |

- `:app:testDebugUnitTest` **does not exist** — the app has `play` and `fdroid` flavors. Use `:app:unitTest` or the flavor-specific task.
- `--tests '<pattern>'` works only on a **concrete** task. On the `test` / `unitTest` / `check` aggregates it fails with `Unknown command-line option '--tests'`. `docs/COVERAGE.md` line 142 shows exactly that broken form; the working one is `./gradlew :core:network:testDebugUnitTest --tests '*CoverageMatrixTest'`.
- Screenshot tasks: `verifyRoborazziDebug` for library modules, but `verifyRoborazziPlayDebug` / `verifyRoborazziFdroidDebug` for `:app`.

## Screenshots are not asserted by `unitTest`

`unitTest` runs the screenshot tests, but **without a Roborazzi flag they neither record nor compare**, so a visual change passes silently. `verifyRoborazziDebug` is the only real assertion — and **CI does not run it either**. Baselines are committed at `<module>/src/test/screenshots`, and the path is written literally in each test (`"src/test/screenshots/$name.png"`) rather than resolved from the extension. Record per module:

```bash
./gradlew :feature:sessions:testDebugUnitTest -Proborazzi.test.record=true
```

## Integration tests need a real server

Live tests live in any package segment named `integration`. They are excluded from `unitTest` and run only under the `integrationTest` task or `-Popencode.integration=true`.

```bash
eval "$(./scripts/dev-server.sh start)"
./gradlew integrationTest
./scripts/dev-server.sh stop
```

- **They skip, not fail, when no server is configured** — a broken run looks green. Pass Gradle properties rather than relying on the environment, because a long-lived daemon does not reliably see a client's env: `-Popencode.it.url=… -Popencode.it.password=… -Popencode.it.directory=… -Popencode.it.fakeProviderUrl=…`.
- `dev-server.sh start` prints `export` lines for stdout and all logs to stderr, so `eval "$(…)"` works. It pins `@opencode/cli@2.0.18`, uses an isolated `HOME`, and starts a fake OpenAI-compatible provider on 4097 (`tools/fake-provider`) that serves scripted turns: models `text`, `reasoning`, `shell`, `edit`, `question`, `subagent`, `error`, `long`, `probe`. State lives in the gitignored `.dev-server/`.
- `scripts/p8-gradle.sh <tasks…>` detaches a long build and prints its log path; tail that instead of blocking.
- `scripts/setup-android-sdk.sh` installs the SDK and writes `local.properties` if absent.

## The coverage contract

`tools/audit-coverage.mjs` **parses `docs/ANDROID_APP_PLAN.md` §7 and §8**. The plan's matrices are machine input; editing them changes what the build demands.

**Adding an API operation** — all three are required or `--strict` fails:

1. **declared** — the exact method and path on `core/network/.../ServerApi.kt`
2. **wired** — reachable from production code (the tool walks wrapper classes transitively across modules, so a surface in another module is fine)
3. **tested** — the declaration is named somewhere in a test source

Step 3 is **syntactic**: a test that names the method and asserts nothing satisfies it. Assert wire properties (the path segments, what the body does and does not carry), not `assertTrue(result.isSuccess)` — see `CoverageGapWireTest`.

**Adding an event type** — payload in `core:model` plus registration in `EventTypes`, and one of four dispatch points: the `when` in `ServerDataSet.apply`, a `RequestCenter` match, or implementing `EventPayload.SessionScoped`. Then regenerate the corpus:

```bash
node tools/gen-event-payloads.mjs   # rewrites core/testing/.../fixtures/event-payloads.jsonl
```

That script hard-asserts exactly **93** named types (94 counting the `rpc.*` family), so adding one means updating that number too. `EventPayloadCoverageTest` asserts no published `data` member is dropped — `ignoreUnknownKeys` makes a missing field invisible everywhere else.

**Five rows are deliberate deviations** Retrofit cannot express, recorded in the tool's `DEVIATIONS` table and in `docs/COVERAGE.md`: `api/fs/read/*`, `DELETE /api/worktree`, `GET /api/event` (SSE), and the two `pty/…/connect` WebSocket upgrades. Do not "fix" them.

`api/opencode-2.0.x/` is vendored from the pinned CLI release and **never hand-edited** — regenerate via `tools/check-api-drift.mjs extract`. `config.schema.json` is copied into `core:data`'s assets by a build task rather than being checked in twice, so editing it changes the app; a schema change also invalidates the config-explorer test, which asserts the generated rows against that file in both directions.

## Modules

- New module: add it to `settings.gradle.kts` and apply an `opencode.android.*` convention plugin from the `build-logic` included build. ktlint and detekt come with the plugin, so a new module cannot forget them. **Most of a feature's dependency list is injected by the convention plugin**, so its `build.gradle.kts` is nearly empty — read `AndroidFeatureConventionPlugin.kt`, not the module file.
- `:core:model` and `:core:testing` are plain **JVM** modules. `android.*` imports there are a compile error. Namespaces are derived from the Gradle path (`:core:model` → `dev.opencode.android.core.model`); no module declares one.
- `TYPESAFE_PROJECT_ACCESSORS` is on — write `projects.core.model`, not `project(":core:model")`.
- `repositoriesMode` is `FAIL_ON_PROJECT_REPOS` — never declare `repositories` in a module build file.
- Feature modules depend on `:core:*` only, **never on each other** (`AndroidFeatureConventionPlugin`). Two deliberate exceptions are `testImplementation` edges so screenshot tests can compose screens: `feature:sessions` → `composer`, `requests`; `feature:review` → `composer`, `sessions`. "Features never touch each other" means `main`, not `test`.
- `core:data` follows a `Surface` / `Commands` / `Store` pattern (`SessionCommands`, `IntegrationSurface`, `ExecutionCommands`, `WorktreeStore`, `ConfigSurface`, `InsightsSurface`). Put new server calls on the matching surface; ViewModels do not call `ServerApi`. `feature:execution` is the one exception — its terminal ViewModel builds its own `PtySocket`, because a WebSocket is not a Retrofit call.
- **`ServerApi` is not injectable.** `ServerApiFactory` builds one Retrofit per (baseUrl, directory, trust flag) over one shared `OkHttpClient`; there is no `@Singleton ServerApi` in the graph. Reach for a surface, not the factory.
- **No hand-written Room migrations.** `OpenCodeDatabase` is at `version = 2` with `fallbackToDestructiveMigration(dropAllTables = true)`. Bumping the version means adding `core/database/schemas/…/3.json` (exported and committed), not writing a `Migration` object.
- `feature/requests` is not just UI: it owns the foreground `ConnectionService`, the notification receiver, its own `AndroidManifest.xml`, and the Hilt module binding `core:data`'s `AttentionSink` and `PresenceSignalsSource` — the inversion exists because `core:data` has no Android platform types.
- Flavors: `play` (ML Kit) / `fdroid` (ZXing, `.fdroid` application id and `-fdroid` version suffix); debug adds `.debug`/`-debug`.
- Vendored xterm.js is committed under `feature/execution/src/main/assets/terminal/`, sourced from the gitignored `.vendor-tmp/xterm`.

## Screens and navigation

Contrary to the usual Compose layout, **no feature module declares a route or a `NavHost`.** Every `@Serializable` destination lives in `:app` under `app/src/main/kotlin/…/navigation/` — `Routes.kt` for the shared graph, then per-phase `SessionHost`, `ReviewHost`, `ExecutionHost`, `AdminHost`, `IntegrationsHost`. A feature contributes a *stateless* screen composable taking a `*UiState` plus lambdas; the matching `*Host` in `:app` wires the ViewModel, state and callbacks.

ViewModels are uniformly `@HiltViewModel` + `@Inject constructor(ServerDataRegistry)`, reading `dataSets.active`. One thing that will waste your time if you go looking: **`feature/insights` is effectively unreachable** — its screen and ViewModel have no route, which is exactly the unfinished screen half of P10. (Its ViewModel is now `@HiltViewModel`, so adding the route will not crash; `audit-viewmodels` keeps it that way.) Only eight of the nine features are destinations.

## Tests

- All tests are in `src/test/kotlin`; there is no `src/androidTest` anywhere. There is **no shared test base class** — each Robolectric test repeats `@RunWith(RobolectricTestRunner::class)`, `@GraphicsMode(NATIVE)`, `@Config(sdk = [34], qualifiers = …)` and its own capture helper.
- **Wire tests drive a real `ServerApi` over MockWebServer**, not a mocked interface — a mock passes whether the route is spelled right or not. The harness is named after the server, not `*Test` (`FakeServer.kt`, `ReviewServer.kt`, `IntegrationsServer.kt`, `ExecutionServer.kt`), as are `*Fixtures.kt` and `VendoredSchema.kt`. Anything in `src/test` not ending in `Test` is a helper.
- `:core:testing` is not fakes or rules. It is recorded fixtures (`src/main/resources/fixtures/`, including the generated `event-payloads.jsonl`), a reader for the vendored V2 spec that walks *up* from the test working directory to `api/opencode-2.0.x/`, and `DevServerHarness` for the live tests. Tests assert against those vendored files rather than against checked-in copies.

## Conventions that differ from the defaults

- **Errors.** `catch (error: Throwable)` at every boundary is the house style; classify with `toActionError()` into `ActionError` / `ActionErrorKind` and throw `ActionFailure`. P6, P7 and P8 each invented a local `ActionFailure` and lost classification across subsystem boundaries; the old names now exist only as typealiases onto the one class. Do not add another.
- **State.** "The server echoes; the client never guesses." No optimistic UI except inbox items, which the server echoes. `server.connected` invalidates rather than patches.
- **Strings.** Every user-visible string goes in `strings.xml`. A literal in `Text()` or `contentDescription` fails CI.
- **Line length — the two linters disagree.** ktlint is 140 and is the limit actually enforced (spotless); it is off in the explicitly listed `src/test` directories in `.editorconfig`. detekt's config declares 120 everywhere, but that task does not currently run (see above), so 140 is the soft ceiling and 120 is what the config will demand once detekt is repaired. Write to 120.
- **detekt bans** `TODO:fixme:`, `FIXME:` and `STOPSHIP:` via `ForbiddenComment`. No detekt baseline, by policy.
- **ktlint rule properties must be written twice** — in `.editorconfig` *and* in the `editorConfigOverride` map in `QualityConventionPlugin.kt`, because Spotless does not carry rule properties across from the editorconfig.
- **`.editorconfig` section headers are globs.** `[*.{kt,kts}]` matches nothing; the sections are written out per extension for that reason.
- ktlint runs `intellij_idea` style with the class/function/parameter-list signature rules disabled, so one-line `data class`es stay one line. That is most of `core:model`; do not reformat them.
- Composables and Roborazzi capture helpers are PascalCase, exempted via `ktlint_function_naming_ignore_when_annotated_with`.
- Commit messages are one imperative sentence that names what was proved ("Make the coverage claim fail the build instead of needing a script"), not a conventional-commit prefix.

## The process guard blocks kills

`.opencode/plugins/process-guard.js` rejects `kill`, `pkill`, `killall`, `os.kill` and `process.kill` **anywhere in a bash command string** — including inside `sh -c "…"`, pipes and `$( )`, because the agent runs in the same process tree as the CLI. Do not route around it. Use:

```bash
./scripts/dev-server.sh start|stop|status   # the only thing allowed to signal these servers
./gradlew --stop
```

## Release

Not a shipped release, and there is no signing material in the tree. `assemblePlayRelease` produces `app-play-release-unsigned.apk`; the `releaseMaterialMissing` task prints what a signed build still needs. Nothing has run on a device or emulator — `docs/RELEASE.md` lists that as the open exit criterion. Do not claim otherwise in a document.

## Documents that are part of a change

`docs/ANDROID_APP_PLAN.md` (architecture, phases, the §7/§8 matrices the audit parses) · `docs/COVERAGE.md` · `docs/CHANGELOG.md` · `docs/OPENCODE_V2_FEATURES.md` (the server contract) · `docs/SECURITY_REVIEW.md` · `docs/PRIVACY_REVIEW.md` · `docs/ACCESSIBILITY_AUDIT.md` · `docs/MANUAL_TEST_MATRIX.md` · `docs/RELEASE.md`.

This repository keeps documents honest against the code rather than aspirational. If a change alters what exists, updating the corresponding document and the phase's "Status" line is part of the change — and a claim the build cannot back is a defect.
