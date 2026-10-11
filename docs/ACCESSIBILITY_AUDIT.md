# Accessibility audit — Phase 10

Plan §5.4 asks for TalkBack semantics, dynamic type and contrast checks. This is the audit, against
the code as it stands, with the checklist exercised mechanically where it can be and written down
where it cannot.

**No device, no emulator, no screen reader.** Everything here is either a check over the sources or
a reading of them. The items marked *not verified here* need a real device and are listed with what
to do, so the next person does not have to rediscover that the difference matters.

## The mechanically checked part

`tools/audit-accessibility.mjs` applies four rules. The first three fire; the fourth is documented
below as a deliberate non-rule.

| Rule | Result |
| --- | --- |
| An `IconButton` with no label anywhere in its call | **0** |
| A text size applied where a named style should be | **0** |
| A merging container that double-reads its subtree | not a rule — see below |
| A `Text` or `contentDescription` holding a literal | **0**, by `tools/audit-strings.mjs` |

The rules were each verified by breaking a real control: removing the `contentDescription` from the
close button in `feature/composer/…/FullScreenEditor.kt` makes the audit report it, and restoring it
makes the audit clean. A checker that cannot fail is not a checker.

**The merging rule is deliberately absent.** Six places use
`Modifier.semantics(mergeDescendants = true) { contentDescription = … }` — for example
`feature/composer/…/ComposerParts.kt:187`, which describes an attachment as
"label. kind. range. size". That is the correct pattern: the description is what a screen reader
reads *instead of* walking each child. A checker that flagged it would push the codebase towards the
worse version, where the whole subtree is read twice. Noted here because "we checked" should say
what was checked.

## The checklist, item by item

### 1. Every control has a name — **verified**

43 files carry `contentDescription`. The rule above covers the failure mode that matters: an
icon-only control announced as "button". Text buttons are named by their own text, which is why the
rule exempts them.

The Compose shape used throughout is the button unlabelled and the `Icon` inside it labelled:

```kotlin
IconButton(onClick = onNavigateBack) {
    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
}
```

That is one accessible control, and the audit's window is the whole call so it can see it.

### 2. Dynamic type — **partly verified**

Every module has a screenshot test that captures at 1.5× font scale, and those baselines are
verified with `verifyRoborazziDebug` (149 images, light and dark). Clipping, overlap and truncation
at 1.5× are therefore caught in CI for the screens that have a capture.

**Not verified here:** 2.0×, the platform's largest accessibility size, and the interaction with
`fontScale` on a real device where the system also scales the display. A screen that survives 1.5×
in a screenshot can still break at 2.0× in a real layout, because a screenshot does not reflow a
scrolling list.

### 3. Contrast — **partly verified**

The fallback colour pairs, including the code palette, are computed by `tools/audit-contrast.mjs`.
The results, and what they do not cover, are in [Computed contrast ratios](#computed-contrast-ratios)
at the end of this document. Two parts need more than a source read:

- dynamic colour, which has no static value and is on by default on Android 12 and later;
- the OpenCode theme import, which the plan describes but the sources do not contain, so there is
  nothing to compute yet.

**What a human must do:** check the dynamic colour schemes on a device. The source cannot give them a
value, and nothing in the repository measures them.

### 4. TalkBack flow — **not verified here**

No screen reader was run. What the code provides and what still needs a device:

| Provided | Where |
| --- | --- |
| Section headings, so a screen reader can jump | `heading()` in the feature screens |
| State as a state, not as a changed label | `stateDescription` on toggles |
| Merged summaries for composite rows | six places, listed above |
| Traversal order follows visual order | No `traversalIndex` is set anywhere, which is the default and is correct |
| Live regions for the composer and the timeline | **not present** — see below |

**Finding A-1 (real, and the one worth fixing first).** The timeline and the composer are live
updates: text streams in, an agent starts and stops, a permission appears. Nothing marks them as a
live region, so a screen-reader user gets no announcement when a turn finishes or a request arrives.
The notification layer covers the case where the phone is locked, and nothing covers the case where
it is not. This needs `liveRegion = LiveRegionMode.Polite` on the status line, and a decision about
how often — a `text.delta` storm announced per frame would be unusable, so it belongs on the turn
boundary (`session.execution.succeeded`, `permission.asked`), not on the stream.

*Update, session redesign:* a turn that finished normally no longer draws a "Turn finished" divider. The newest turn
end keeps its polite live region on an invisible node carrying the outcome as its description, so the announcement
survives the divider leaving the screen; a failed or interrupted turn still draws a caption, which carries the region
itself. `TurnDividerLiveRegionTest` covers the three cases. Tool rows are single `toggleable` nodes that announce
their expanded state, and the user's bubble is read as "You: …" because a screen reader cannot see which side it is on.

**Finding A-2.** The keyboard-shortcut layer added in this phase (Ctrl+P for the command palette,
leader-key combinations) is a *visible* feature and has no non-keyboard equivalent yet: a
switch-access or switch-scan user cannot reach it. The commands themselves are reachable through
their screens, so nothing is unreachable — but the shortcuts should not be described in the UI as
the primary way to do something until there is a second route.

### 5. The WebView terminal — **a special case, and the hardest one to make accessible**

`feature/execution/…/TerminalWebView.kt` renders xterm.js. A terminal is a grid of characters with
no semantics at all: every glyph is a `Text` in the DOM as far as a screen reader is concerned, and
navigation is meaningless.

**This is a known and accepted limitation, not an oversight.** The alternatives are worse: a
screen-reader-accessible terminal emulator is not a thing that exists, and reimplementing VT
semantics in Compose would be a second terminal that is worse at the actual job. What the app can do
is offer the transcript — the session's own message list already carries every tool's output, is
built from semantics, and is navigable. The terminal is the power feature and the transcript is the
accessible one, and the UI should say so rather than leaving a user to discover it.

### 6. Motion and animation

No `AnimatedVisibility` is used with a duration long enough to be a problem, and the app respects
the system's animation scale through Compose's defaults. **Not verified here** — there is no reduced
motion test.

### 7. Focus order and dismissal

`BackHandler` is wired on the sheets and the full-screen editor. Dialogs use Material 3 `Dialog`,
which handles focus trapping and the back gesture. **Not verified here** on a device with a keyboard
or a switch.

## Summary

| Item | State |
| --- | --- |
| Control names | Verified mechanically, 0 findings |
| Literal strings in semantics | Verified mechanically, 0 findings |
| Dynamic type at 1.5× | Verified by screenshot baselines in CI |
| Dynamic type at 2.0× | Not verified; needs a device |
| Contrast | Fallback pairs computed (see the end); dynamic colour not computed |
| TalkBack flow | Not verified; two findings, A-1 and A-2 above |
| Terminal accessibility | Accepted limitation, with an accessible alternative in the transcript |
| Reduced motion | Not verified |

## What a release still has to do

1. Fix **A-1**: a live region on the turn boundary, not on the stream. It is a small change and it
   is the difference between the app being usable and not with a screen reader.
2. Check the dynamic colour schemes on a device, which `tools/audit-contrast.mjs` cannot compute. The
   fallback pairs and the code palette are already computed (see the end of this document).
3. Capture the 1.5× baselines' siblings at 2.0× for the timeline and the composer specifically, which
   are the two that scroll.
4. Run the app on a device with TalkBack and the largest font size, and record what breaks. That is
   the only way the "not verified" rows above become verified.

## Computed contrast ratios

Computed on 2026-10-10 by `node tools/audit-contrast.mjs`. Its `tools/audit-contrast.test.mjs` passes
12 tests, including black on white at 21:1, a colour against itself at 1:1, and `#767676` on white at
4.54:1, which is worked out by hand in the test. The script reads the colours from the Kotlin sources
and keeps no copy of its own. It computes WCAG 2.x relative luminance and the ratio of each pair, with
translucent colours composited in sRGB first. It reports and exits 0, like the other audits. It does
not change any colour.

**Dynamic colour is not covered.** `OpenCodeTheme` uses Material You on Android 12 and later (API 31)
when `dynamicColor` is true, and `MainActivity` calls it with the default, so most devices get a
wallpaper-derived scheme. The source has no value for that scheme. Every figure below is for the
static fallback schemes, `OpenCodeLightColors` and `OpenCodeDarkColors`, which is what Android 11 and
earlier see. The diff palette and the code blocks take their colours from whichever scheme is active,
so on a dynamic device they are not these figures.

### What was measured

Each scheme has 72 pairs, each a text or component colour against the background it sits on.

- **Scheme roles.** The core pairs (`onSurface`, `onSurfaceVariant`, `onBackground`, `onPrimary`,
  `onSecondary`, `onError`, and the `on…Container` pair for primary, secondary and error), plus
  `onTertiaryContainer`, `onSurfaceVariant` on `surfaceVariant`, the Snackbar pair (`EventInspectorScreen`
  uses one), and the
  accent roles the screens use for text or status marks on `surface` (`primary`, `secondary`,
  `tertiary`, `error`, and `outline` for the disconnected server dot).
- **Diff palette.** For each row kind (context, selected, added, removed, hunk header): the row's text,
  line number, marker, and each token colour (keyword, string, number, comment, annotation,
  punctuation) against the row's background. The token colours are drawn on added and removed rows
  too, because `highlightedPlain` in `DiffTable.kt` applies them whatever the row kind. They apply only
  to a file with a highlighted language.
- **Code blocks and links.** The Markdown code block's label and tokens on `surfaceVariant`, and the
  link colour `#1A73E8` on `surface`.
- **File viewer selection.** A selected line's line number on `secondaryContainer`, and its text (see
  the assumptions below).

### Results

Light scheme: 26 of 72 pairs are below 4.5:1, and 4 are below 3:1. Dark scheme: 20 of 72 are below
4.5:1, and 2 are below 3:1.

A pair is listed if it is below 4.5:1 in either scheme, and both schemes' ratios are shown. `*` is
below 4.5:1 and `**` is below 3:1. Ratios are to two decimals.

| Pair | Light | Dark |
| --- | --- | --- |
| onSecondary / secondary | 4.11 * | 4.69 |
| secondary / surface | 3.94 * | 7.10 |
| outline / surface | 3.31 * | 5.01 |
| diff gutter on context row | 3.04 * | 4.14 * |
| diff number on context row | 3.94 * | 7.10 |
| diff comment on context row | 3.85 * | 5.28 |
| diff gutter on selected row | 2.77 ** | 3.67 * |
| diff string on selected row | 3.99 * | 4.48 * |
| diff number on selected row | 3.13 * | 5.34 |
| diff comment on selected row | 3.41 * | 4.46 * |
| diff gutter on added row | 2.87 ** | 2.85 ** |
| diff string on added row | 4.36 * | 3.05 * |
| diff number on added row | 3.42 * | 3.64 * |
| diff comment on added row | 3.57 * | 3.33 * |
| diff annotation on added row | 5.38 | 3.18 * |
| diff gutter on removed row | 2.79 ** | 2.97 ** |
| diff string on removed row | 4.05 * | 3.43 * |
| diff number on removed row | 3.18 * | 4.09 * |
| diff comment on removed row | 3.44 * | 3.54 * |
| diff annotation on removed row | 5.00 | 3.57 * |
| diff gutter on hunk header | 2.88 ** | 3.65 * |
| diff string on hunk header | 4.39 * | 4.43 * |
| diff number on hunk header | 3.45 * | 5.29 |
| diff comment on hunk header | 3.59 * | 4.43 * |
| code string on code block | 4.39 * | 4.43 * |
| code number on code block | 3.45 * | 5.29 |
| code comment on code block | 3.59 * | 4.43 * |
| markdown link on surface | 4.32 * | 4.39 * |

- Of the scheme roles, the light scheme fails on `onSecondary`/`secondary` and `secondary`/`surface`
  as text. `outline`/`surface` is a non-text component, so only its 3:1 verdict applies, and it passes.
  The dark scheme passes every scheme role.
- Line numbers are text, so 4.5:1 applies to them. They are below 3:1 on the selected, added, removed
  and hunk-header rows in light, and on the added and removed rows in dark.
- In a Markdown code block, string and comment tokens fail 4.5:1 in both schemes, and number tokens
  fail in light. The link colour fails 4.5:1 in both schemes.
- Dark annotation tokens on added and removed rows fail 4.5:1 (3.18 and 3.57). Light annotation tokens
  on the same rows pass.

### Not computed

- **Dynamic colour (Material You, API 31 and later).** See above.
- **Terminal.** `index.html` sets only the page background, `#101216`. The terminal's foreground and
  ANSI colours are xterm.js defaults, which are not in the app source.
- **Colours from the server or the user.** Agent colours (`Pickers.kt`), and project and worktree
  colours.
- **Camera-unavailable message.** `QrCodeScannerView` draws `Color.White` text over a `Color.Black` box.
  A camera view sits in the same box, and what it shows after a failed bind is not in the source, so
  this pair is not computed.
- **OpenCode theme import.** The plan describes it, and the sources do not contain it, so no sample
  theme was computed.
- **File viewer text.** `TextFileView` sets no colour on its text, so it takes the content colour of a
  host `Surface` that the file does not show. The script assumes `onSurface` for that host, so that
  pair is an assumption and not a reading.

### Assumptions the figures depend on

- Context rows sit on `surface`, as `DiffFileCard` and the tool card's `Surface` set it. The split
  diff in `ReviewScreen` adds no `Surface` of its own, and its host was not traced. It is assumed to be
  `surface`.
- The link is measured on `surface`. The transcript's host was not traced.
- `background` and `surface` have the same values in both fallback schemes, so `onBackground` on
  `background` gives the same ratio as `onSurface` on `surface`.
- The light or dark diff variant is chosen by the luminance of `surface` against 0.5, as
  `DiffColors.of` does.

### What this does not show

- These are ratios of the source colours. Nothing was rendered: no screenshot, device, font weight or
  font size was checked. A pair that passes 4.5:1 can still read poorly in a small, thin font.
- The script does not know the size of any text, so it reports both verdicts. Large text needs 3:1,
  meaning 18 pt, or 14 pt bold. The table does not say which applies to a given use.
- `TextFileView` in `FileBrowserScreen.kt` draws each line as plain `line.text`.
  `CodeHighlighter.highlightAll` runs for the file, and the file's doc comment says it is highlighted,
  but no token colour reaches the screen. The token pairs above therefore do not apply in the file
  viewer.
