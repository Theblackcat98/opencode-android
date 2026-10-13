# MOTION.md — opencode-android

The motion language for this app. Decisions, not inventory: anything a grep can answer
is derived on demand and never persisted here.

Each entry carries an **intent** (design owns this) and a **binding** (engineering owns
this), plus provenance: `DECIDED` (ratified), `OBSERVED` (dominant in the codebase, never
ratified), `OPEN` (no convention — stop and ask, do not pick).

## The design system

This app is Material 3, Expressive-leaning. `OpenCodeTheme` wires
`motionScheme = expressiveMotionScheme()` explicitly rather than inheriting the silent
default — the scheme is a deliberate choice, recorded here.

- **Intent** — the app's motion voice is M3 Expressive: confident spatial movement on
  containers and surfaces, quiet effects on everything else.
- **Binding** — `MaterialTheme.motionScheme` (expressive), resolved in
  `core/designsystem/.../theme/Theme.kt`.

Where the product's own language is silent, the fallback is pragmatic M3 Expressive
(resolution rung 1). A decision resolved from the fallback says so in its entry.

## Navigation transitions

`app/.../navigation/Transitions.kt` holds the whole language as three `DestinationTransitions`
sets. The literal `tween()` calls live there, in the token definitions, which is where
they are permitted — no call site names a duration or a spec of its own. The `NavHost`
default is the Forward set; TopLevel and Container are per-destination overrides.

### Top-level destinations: cross-fade

Servers, Home, Chat, Settings — the server picker and the three bottom-bar tabs.

- **Intent** — switching tabs is a change of place, not a journey. No direction is
  promised, because there is none: these are unordered peers.
- **Binding** — `DestinationTransitions.TopLevel`: fade in 400ms, fade out 200ms.
- **Provenance** — `DECIDED`. Replaces the Navigation 700ms default (T-001), which held
  both screens at partial opacity for most of a second on every navigation.

### Forward: slide in with a fade, effects-only out, inverse on back

Everything pushed above a top-level screen: session, shells, terminals, admin screens,
manage destinations.

- **Intent** — a push is a descent into a hierarchy. The new screen arrives from the
  right; going back returns the way it came (T-024). Exits are quiet: something leaving
  should not hold attention on its way out (T-010).
- **Binding** — `DestinationTransitions.Forward`: enter slides the full width with a fade
  beside it (400ms); push exit is fade-only (200ms); pop enter is fade-only; pop exit is
  the entrance reversed.
- **Provenance** — `DECIDED`.

### Container: session to review, session to files

- **Intent** — the review and the file browser are not new places; they are the session's
  own content opening up. The destination grows out of its origin rather than sliding in
  from an edge.
- **Binding** — `DestinationTransitions.Container`: scale 0.92 → 1.0 with a fade (400ms);
  reversed on back. This is the container-transform language anchored to its origin, not a
  true shared-element transition.
- **Provenance** — `DECIDED`, with a known gap (see `OPEN` below): a real shared-element
  transition needs `SharedTransitionLayout` wiring in both screens, which does not exist
  yet. The anchored zoom is the deliberate stand-in until it does.

## Per-surface decisions

### Expressive spatial stays off elements read as data

- **Intent** — bounce on a number reads as instability, not personality. Session metadata,
  token counts, usage figures and plotted values settle because they are correct, not
  because they stopped wobbling (T-004).
- **Binding** — the expressive scheme governs containers and surfaces. Nothing in the
  app's own code puts an expressive spatial spring on a data-read element; transitions
  use the default/effects tiers.
- **Provenance** — `DECIDED`.

### Back-stack discipline feeds the transitions

- **Intent** — a repeated intent never stacks a second copy of the screen it names, so a
  transition never plays for a destination that is already there.
- **Binding** — every `navigate()` sets `launchSingleTop` (the rule is in `OpenCodeApp`'s
  KDoc); notification and shell intents reset with `popUpTo(ServersRoute)` before pushing
  Home + Session/Shells.
- **Provenance** — `DECIDED`.

### Sheets, dialogs, snackbars: the platform's motion

- **Intent** — the new-session sheet, the palette dialog, the snackbar host and the armed
  pill do not need a product voice; the platform's is correct and familiar.
- **Binding** — no custom specs. Component defaults, which resolve through the wired
  motion scheme.
- **Provenance** — `OBSERVED`.

### In-place appearance: fade, never travel

Menus, popups, error lines and empty states appear where they will sit.

- **Intent** — a component appearing in place has no path to retrace; it fades in and
  fades out (T-010, T-011).
- **Binding** — `OBSERVED`: the codebase's `AnimatedVisibility` and `animateContentSize`
  call sites run on defaults. No product token exists for these yet.
- **Provenance** — `OBSERVED`. A token is `OPEN` until someone defines the durations.

### The palette rows announce selection

- **Intent** — arrow-key selection in the command palette is state a TalkBack user must
  hear, not just see.
- **Binding** — `Modifier.selectable(selected = …, role = Role.Button)` on the rows, which
  publishes the selected semantics where `clickable` plus a manual role stayed silent.
- **Provenance** — `DECIDED`.

## OPEN

- **True shared-element transitions** for session → review/files. The container zoom is
  the stand-in; wiring `SharedTransitionLayout` through both screens is a design decision
  with real work attached. Do not improvise one.
- **Stagger budgets** for list entrances. No screen staggers today; when one does, the
  budget is ~300ms from first start to last start (T-013), not a per-item delay.
- **Skeleton loaders.** Loading states are spinners and empty text today. If skeletons
  arrive, they match the eventual structure and follow the delay → fade-in → minimum
  dwell → cross-fade sequence (T-019).
- **In-place motion tokens.** The durations for fades and expands of components (as
  opposed to destinations) are not tokenized. Literal specs for these remain `OPEN` —
  do not scatter them; define the token first.
