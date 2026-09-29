# Accessibility audit — Phase 10

**Scope.** This audits the screens that exist. The usage dashboard is included in the mechanical checks
(no unlabelled controls, no literal strings) but has no screenshot baseline and is not reachable from the
app. The widget, the tile, the command palette, session tabs, the adaptive layouts and the theme import do
not exist and are not audited; each will need this audit run over it (Phase 11).

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

### 3. Contrast — **not verified here**

No contrast ratio is computed anywhere, and no tool computes one. Material 3's `ColorScheme` roles
give the pairs their contrast, so the risk is concentrated in two places and both need a device or
a colour tool rather than a code reading:

- the code typography, which is a fixed palette not drawn from the `ColorScheme` (see §5);
- the OpenCode theme import, which does not exist yet (Phase 11 item 11.13) and, when built, maps
  arbitrary user tokens onto a Material scheme and could produce a pair below 4.5:1.

**What a human must do:** compute the contrast of every foreground/background pair the code palette
and, once the import exists, a sample OpenCode theme can produce. Both are finite and checkable offline; nothing here does it.

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

**Finding A-2 (a requirement on a feature that does not exist yet).** The keyboard-shortcut layer, meaning
Ctrl+P for the command palette and leader-key combinations, is not built: it is Phase 11 item 11.9. When it
is, it must have a non-keyboard route, because a switch-access or switch-scan user cannot reach a shortcut,
and the UI must not describe a shortcut as the primary way to do something until a second route exists.
Nothing is unreachable today, since every command is reachable through its own screen.

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
| Contrast | Not verified; needs a colour tool and a theme sample |
| TalkBack flow | Not verified; two findings, A-1 and A-2 above |
| Terminal accessibility | Accepted limitation, with an accessible alternative in the transcript |
| Reduced motion | Not verified |

## What a release still has to do

1. Fix **A-1**: a live region on the turn boundary, not on the stream. It is a small change and it
   is the difference between the app being usable and not with a screen reader.
2. Run the contrast check over the code palette, and over a sample imported OpenCode theme once the
   import exists. Both are finite sets and can be done offline.
3. Capture the 1.5× baselines' siblings at 2.0× for the timeline and the composer specifically, which
   are the two that scroll.
4. Run the app on a device with TalkBack and the largest font size, and record what breaks. That is
   the only way the "not verified" rows above become verified.
