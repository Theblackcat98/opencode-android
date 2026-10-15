# Accessibility release-gate audit — issue #29

This records what the repository can check automatically and what still requires a device. It is evidence, not a claim that TalkBack or every supported display has been exercised.

## Automated gates

| Check | Coverage | Current result |
| --- | --- | --- |
| `node tools/audit-accessibility.mjs` | Icon-only controls without names and hardcoded UI font-size overrides; named `Typography(...)` definitions are not mistaken for call-site overrides | 0 findings |
| `node tools/audit-contrast.test.mjs` | Source-derived pairs from both fallback schemes, diff rows, code blocks, links, and file selection; WCAG 2.x text threshold 4.5:1, component threshold 3:1 | 12 tests pass; 144 pairs measured; no unmeasured pairs or threshold failures |
| `:core:designsystem:testDebugUnitTest` | OpenCode V2 theme parsing, mode-specific palettes, aliases, unsupported tokens, storage round-trip, size/malformed input, and contrast rejection | Pass |
| `:feature:requests:testDebugUnitTest` | Permission request title semantics and request UI behavior | Pass; new permission title is a polite live region |
| `:feature:sessions:testDebugUnitTest` | Session timeline announcement semantics and composer saved-state behavior | Pass; draft text and caret survive saved-state recreation |
| CI workflow | The accessibility audit is required to report zero findings; contrast tests run on every push and pull request | Enforced in `.github/workflows/ci.yml` |

Imported OpenCode V2 themes are normalized to the app's supported Material roles and checked before preview or apply: supported text pairs below 4.5:1 reject the import, with checks across base and raised surfaces; outline components below 3:1 against the background or base surface also reject. Unsupported tokens are disclosed rather than executed or copied into storage. Parser tests exercise contrasting light/dark palettes, low-contrast raised surfaces, and outline rejection. Material You dynamic colors are separate from this import-time check.

The timeline already announces completed and failed/interrupted turns at turn boundaries. Permission and inline-form titles now use polite live regions. The announcement is attached to the title when a request appears—not to streamed text or each keystroke.

## What changed for the approved issues

- **#26 — adaptive list/detail navigation:** the session list and transcript already use Compose saveable list state, and session identity/tab selection already survive recreation. The composer now saves its `TextFieldValue` (draft and selection/caret) through saved state, keyed to the active session. `ComposerStateRestorationTest` covers draft and caret restoration. A physical foldable/resizable-device run remains outstanding.
- **#27 — safe theme import:** added a bounded OpenCode V2 parser and read-only preview/apply/reset flow. The importer resolves only supported color roles and references, reports unsupported tokens, checks contrast, stores only normalized palette values, and leaves source files untouched. The import tests cover validation and persistence.
- **#29 — accessibility release gates:** fixed diff and selected-file-line contrast, corrected the font-size audit's false positives for named typography roles, added request/form live regions and a permission semantics test, and made contrast checks a CI gate. The existing Home “New session” label and successful-turn live region were already present in the baseline.

## Fallback palette contrast results

`tools/audit-contrast.mjs` reads the source values rather than keeping a duplicate palette. It measures 72 pairs in each static fallback scheme. All measured text pairs meet 4.5:1; all measured non-text pairs meet 3:1. The disconnected-server outline is a component, not text.

| Fallback | Text pairs below 4.5:1 | Pairs below 3:1 | Lowest text ratio |
| --- | ---: | ---: | --- |
| Light | 0 | 0 | 4.85:1 — diff number on a removed row |
| Dark | 0 | 0 | 4.52:1 — selected file line number |
| Outline component | n/a | 0 | 3.31:1 light; 5.01:1 dark |

The diff now uses subtle theme-derived add/remove washes, full-strength text/gutter colors, and a low surface for hunk headers. Selected file line numbers use `onSecondaryContainer` against `secondaryContainer`. The contrast test fails if any measured text pair regresses below 4.5:1 or the outline falls below 3:1.

## Not verified by these source checks

- **TalkBack speech and focus order:** Compose semantics tests verify live-region properties, but they do not prove what TalkBack speaks or when. Run on a device with TalkBack, including permission arrival, turn completion, forms/dialogs, and navigation order. See `docs/MANUAL_TEST_MATRIX.md` K2–K5.
- **Dynamic color:** Material You derives its scheme from the device wallpaper on Android 12+, so it has no static source value. The contrast tool measures only `OpenCodeLightColors` and `OpenCodeDarkColors`.
- **Largest font size across every screen:** the repository has 1.5× screenshot coverage for its tested screens, and the manual matrix records a partial 2.0× device check. The remaining screens still need review at the platform maximum.
- **Terminal:** xterm.js in a WebView is not a navigable text transcript. The session transcript is the accessible alternative; the terminal's ANSI palette is not part of the static app palette.
- **Focus order with keyboard/switch access and reduced motion:** no complete real-device check was made for these.
- **Data-dependent colors and surfaces:** project/agent/worktree colors come from data. File text inherits its host surface content color; the source audit records the `onSurface` assumption for the selected-line text pair.

## Remaining release actions

1. Run the TalkBack checks in K2–K5 and record actual spoken announcements and traversal order.
2. Check dynamic Material You colors on representative Android 12+ devices; the static fallback check does not cover them.
3. Complete the largest-font checks across all screens, not only the sampled flows in the manual matrix.
4. Keep the terminal transcript available as the accessible alternative, and verify the flow with a screen reader.

These remaining device checks are why K2–K6 are not represented as a blanket accessibility pass, even though the fixed fallback palettes and automated source checks now pass.
