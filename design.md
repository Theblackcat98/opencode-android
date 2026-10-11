---
name: Developer Terminal Canvas
colors:
  surface: '#131313'
  surface-dim: '#131313'
  surface-bright: '#3a3939'
  surface-container-lowest: '#0e0e0e'
  surface-container-low: '#1c1b1b'
  surface-container: '#201f1f'
  surface-container-high: '#2a2a2a'
  surface-container-highest: '#353534'
  on-surface: '#e5e2e1'
  on-surface-variant: '#d7c3b7'
  inverse-surface: '#e5e2e1'
  inverse-on-surface: '#313030'
  outline: '#9f8d83'
  outline-variant: '#52443c'
  surface-tint: '#ffb787'
  primary: '#ffd6bd'
  on-primary: '#502402'
  primary-container: '#fab283'
  on-primary-container: '#76421d'
  inverse-primary: '#88512a'
  secondary: '#a7c8ff'
  on-secondary: '#003060'
  secondary-container: '#0361b6'
  on-secondary-container: '#cbddff'
  tertiary: '#e7d7ff'
  on-tertiary: '#3d1a73'
  tertiary-container: '#d0b5ff'
  on-tertiary-container: '#5d3d95'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#ffdcc7'
  primary-fixed-dim: '#ffb787'
  on-primary-fixed: '#311300'
  on-primary-fixed-variant: '#6b3a15'
  secondary-fixed: '#d5e3ff'
  secondary-fixed-dim: '#a7c8ff'
  on-secondary-fixed: '#001b3c'
  on-secondary-fixed-variant: '#004788'
  tertiary-fixed: '#ebdcff'
  tertiary-fixed-dim: '#d4bbff'
  on-tertiary-fixed: '#260058'
  on-tertiary-fixed-variant: '#54348b'
  background: '#131313'
  on-background: '#e5e2e1'
  surface-variant: '#353534'
typography:
  display-lg:
    fontFamily: Roboto Flex
    fontSize: 57px
    fontWeight: '400'
    lineHeight: 64px
  headline-lg:
    fontFamily: Roboto Flex
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
  headline-md:
    fontFamily: Roboto Flex
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  headline-sm:
    fontFamily: Roboto Flex
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  title-lg:
    fontFamily: Roboto Flex
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 28px
  title-md:
    fontFamily: Roboto Flex
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
  title-sm:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
  body-lg:
    fontFamily: Roboto Flex
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
  label-md:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
  label-sm:
    fontFamily: Roboto Flex
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 16px
  code-body:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
  code-small:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 15px
  code-inline:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-tablet: 1.5rem
  margin: 1rem
  margin-mobile: 1rem
  margin-tablet: 1.5rem
  margin-desktop: 2rem
  space-xxs: 0.125rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.75rem
  space-lg: 1rem
  space-xl: 1.5rem
  space-xxl: 2rem
---

# Developer Terminal Canvas Design System & Specification

## Brand & Style

This design system is an uncompromising, mobile-first design system engineered for software developers, AI code-orchestration clients, and terminal power users. The system marries the high-density ergonomics of command-line developer tooling with the fluid, tactile touch conventions of Material Design 3.

### Design Movement
- **Developer Minimalist & Nocturnal Utility**: Grounded in deep, near-black "Ink" tonal surfaces contrasted with a warm terminal peach primary accent, electric dev blue, and violet syntax highlights.
- **Tone**: Focused, authoritative, ultra-tactile, and developer-centric. It removes unnecessary decorative flourish in favor of razor-sharp hairline dividers, precise syntax coloration, and information-dense containers.
- **Emotional Response**: The interface evokes terminal proficiency, operational confidence, and zero eye fatigue during extended nocturnal debugging or pull-request reviews on handheld screens.

## Colors

The color system implements a structured tonal ramp designed for nocturnal code review, syntax legibility, and high-contrast accessibility.

### Palette Architecture
- **Primary (`#FAB283` - Warm Terminal Peach)**: Primary calls-to-action, active selection tabs, active diff indicators, and syntax language keywords.
  - On-Primary: `#3A1A04`
  - Primary Container: `#5A3217`
  - On-Primary Container: `#FFDBC8`
- **Secondary (`#5C9CF5` - Electric Dev Blue)**: Secondary interactive triggers, branch status pills, numeric literals, and telemetry references.
  - On-Secondary: `#00315F`
  - Secondary Container: `#1B3F6B`
  - On-Secondary Container: `#D4E3FF`
- **Tertiary (`#9D7CD8` - Electric Violet)**: Tool execution logs, string literals, sub-agent chips, and auxiliary data tags.
  - On-Tertiary: `#2E1260`
  - Tertiary Container: `#45307A`
  - On-Tertiary Container: `#EADDFF`
- **Neutral Surface Ramp**:
  - `surface` / `surface-container-lowest`: `#0A0A0A` (Root canvas, terminal background)
  - `surface-container-low` / `surface-container`: `#141414` (Resting cards, composer dock, code block backgrounds)
  - `surface-container-high`: `#1E1E1E` (Elevated modals, bottom sheets, active card states)
  - `surface-container-highest`: `#282828` (Hunk headers, tool headers, chip fills)
  - `on-surface`: `#EEEEEE` (High-contrast body text and headers)
  - `on-surface-variant`: `#B8B8B8` (Secondary reading text, file paths, operators)
  - `outline`: `#808080` (Active focus rings and icons)
  - `outline-variant`: `#3C3C3C` (1px hairline structural container borders)

### Developer & Syntax Semantics
- **Syntax Keyword**: `#FAB283`
- **Syntax String**: `#9D7CD8`
- **Syntax Number**: `#5C9CF5`
- **Syntax Comment**: `#808080`
- **Syntax Annotation**: `#E06C75`
- **Syntax Punctuation / Operator**: `#B8B8B8`
- **Diff Added Wash**: `#1E4A2A` (Background) / `#C6EFD0` (Text)
- **Diff Removed Wash**: `#6B1E26` (Background) / `#FFDADB` (Text)
- **Error / Destructive**: `#E06C75` / Container: `#6B1E26`
- **Warning**: `#F5A742` / Container: `#5C3800`
- **Success**: `#7FD88F` / Container: `#1E4A2A`

## Typography

Typography uses a strict two-engine structure to segregate prose comprehension from code analysis:

1. **System Sans (`Roboto Flex`)**: Powers structural chrome, dialog headings, application navigation, markdown body descriptions, and interactive button text.
2. **Developer Monospace (`JetBrains Mono`)**: Powers code blocks, diff hunks, terminal logs, Git hashes, file paths, and syntax-highlighted editor spans.

### Hierarchy & Rules
- **Code Line Height (138%)**: Monospace code body is set to 13px size on an 18px line height. This rhythm prevents code crowding on compact phone screens and ensures adequate vertical spacing for touch selection and line-level comments.
- **Monospace Segregation**: Inline code chips, markdown fences, and diff lines must never inherit proportional fonts or loose letter tracking.
- **Mobile Readability**: Screen titles scale gracefully between `headline-sm` (24px) for mobile headers and `headline-lg` (32px) for root-level views.

## Layout & Spacing

The layout is built around a mobile-first, density-aware fluid grid structured on a 4px (0.25rem) base increment.

### Screen Adaptability & Breakpoints
- **Compact (< 600dp / Mobile Portrait)**:
  - 4-column fluid layout with 16px (`margin-mobile`) screen edge margins and 16px (`gutter`) column gaps.
  - Docked 2-row bottom composer bar and full-width diff hunks with horizontal pan support.
- **Medium (600dp – 840dp / Foldables & Small Tablets)**:
  - 8-column layout with 24px margins. Navigation switches from bottom app bars to a compact navigation rail.
- **Expanded (> 840dp / Tablets & Desktop DeX)**:
  - 12-column dual-pane layout. Left drawer (320dp fixed width) hosts session lists and Git worktrees; right canvas hosts the transcript editor and diff inspection views.

### Touch Ergonomics & Keyboard Management
- **Minimum Tap Target**: Every interactive element must satisfy a strict 48x48dp minimum boundary (`minHeight = 48dp`, `minWidth = 48dp`).
- **Software Keyboard Management**: Mobile input rows, docks, and sheets must strictly coordinate with software keyboard insets (`imePadding`) and system navigation bar insets to prevent UI occlusion.

## Elevation & Depth

This design system avoids blurry, muddy drop shadows in dark mode, maintaining contrast and structural clarity through flat tonal surfaces and hairline boundaries:

### Tonal Surface Stacking
Depth is conveyed exclusively through value shifts in the `surface-container` hierarchy:
1. **Level 0 (Canvas Baseline - `#0A0A0A` / `#131313`)**: Root application background and raw terminal viewports.
2. **Level 1 (Resting Base - `#141414` / `#201F1F`)**: Cards, list containers, and the docked composer background.
3. **Level 2 (Raised - `#1E1E1E` / `#2A2A2A`)**: Bottom sheets, dropdown menus, and interactive cards in active states.
4. **Level 3 (Highest - `#282828` / `#353534`)**: Tool header accordions, hunk headers, and banner cards.

### Hairline Borders
Every container, code fence, input field, and card utilizes a 1px solid hairline outline (`outline-variant` `#3C3C3C` / `#52443C`). On active focus or keyboard navigation, borders transition sharply to `outline` (`#808080` / `#9F8D83`) or `primary` (`#FAB283` / `#FFD6BD`) with no drop shadow blur.

## Shapes

The shape system balances technical structure with tactile Material 3 ergonomics.

### Shape Geometry Tokens
- **Micro Badges (`badge` - 6px)**: Status chips, compact commit hash badges, and execution duration pills.
- **Interactive Controls & Code Blocks (`button`, `chip`, `codeBlock` - 8px / 0.5rem)**: Standard buttons, filter chips, syntax containers, and text fields.
- **Containers & Surface Cards (`card` - 12px / 0.75rem)**: Primary content cards, session tiles, and conversational bubbles.
- **Modals & Drawers (`sheet` - 16px / 1rem)**: Bottom sheet drag surfaces and floating navigation sheets.
- **Full Pill (`full` - 9999px)**: Floating action send buttons, status indicator dots, and pill tab indicators.

## Components

### Buttons
- **Primary Filled**: `#FAB283` background with high-contrast `#3A1A04` text, 8px border radius, 48dp minimum touch target. 0dp elevation.
- **Secondary Outlined**: Transparent background, 1px `#3C3C3C` border, `#FAB283` or `#EEEEEE` label text.
- **Destructive**: `#6B1E26` background with `#FFDADB` text and 1px `#E06C75` border on active hover/press.
- **Icon Buttons**: 48x48dp bounding box enclosing a 24dp centered icon, tinting from `#B8B8B8` to `#FAB283` on press with an in-bounds ripple.

### Chips & Status Badges
- **Status Badges**: 6px border radius, horizontal 8px, vertical 2px padding. Text set in `label-sm` (`JetBrains Mono` 11px).
  - *Active / Running*: `#1B3F6B` background with `#5C9CF5` text.
  - *Passed / Merged*: `#1E4A2A` background with `#7FD88F` text.
  - *Failed / Error*: `#6B1E26` background with `#E06C75` text.
- **Filter Chips**: 8px border radius, horizontal 12px, vertical 8px padding. Resting on `#282828` with `#B8B8B8` text, shifting to `#5A3217` fill and `#FAB283` label when selected.

### Input Fields & Mobile Composer Dock
- **Architecture**: Docked two-row mobile architecture pinned directly above keyboard insets (`imePadding`).
  - *Row 1 (Context Bar)*: Horizontally scrollable chip row showing target model (`Claude 3.7 Sonnet`), agent persona, and attachment tokens.
  - *Row 2 (Input Row)*: High-contrast auto-expanding text field (1 to 4 lines maximum) resting on `#141414` with a 1px `#3C3C3C` border. Flanked by a 48dp attachment button (`+`) and a 48dp pill send button (`#FAB283` fill).
- **Terminal Input Mode**: Switches dynamically to `JetBrains Mono` typography, disabling autocorrect and smart punctuation.

### Code Blocks & Diff Viewers
- **Code Block Container**: 8px rounded container on `#141414` base with 1px `#3C3C3C` border. Features a 32dp header bar showing language syntax tag (in uppercase `code-small`), file path, and a 1-tap copy button with tactile haptic feedback. Supports horizontal code scrolling with pinned line numbers.
- **Diff Table**: Dual-column line gutters (`#6E6E6E` at 60% alpha) in `code-small`. Hunk dividers utilize `#282828` background with `#FAB283` offset notations (`@@ -14,7 +14,9 @@`). Added lines feature a `#1E4A2A` wash; removed lines feature a `#6B1E26` wash.

### Cards & Accordions
- **Cards**: 12px radius, `#141414` fill, 16px internal padding, wrapped in a 1px `#3C3C3C` border. Ripple effect elevates background to `#1E1E1E` on touch.
- **Tool Accordions**: Compact 40dp summary header with tool execution icon, duration badge, and expansion chevron. Expands to reveal a scrollable terminal execution drawer.

### Checkboxes, Radios, & Switches
- **Checkboxes & Radios**: 20x20dp interactive core with 48x48dp tap target padding. Inactive outline in `#808080`, checked fill in `#FAB283` with `#3A1A04` checkmark.
- **Switches**: Material 3 track-and-thumb geometry; `#282828` inactive track with `#808080` thumb; active state transitions to `#5A3217` track with `#FAB283` thumb.

---

## Codebase Architecture, Purpose & Foundation

### 1. Codebase Purpose, Taglines & Real Copy
*Source of truth: `README.md`, `AGENTS.md`, `docs/ANDROID_APP_PLAN.md`*

- **Product Name**: **OpenCode for Android**
- **Target Backend**: **OpenCode V2** (`@opencode/cli` 2.0.18)
- **Lead Tagline** (*verbatim from `README.md`*):
  > *"A native Android client for OpenCode V2. It connects to an OpenCode server on your local network, or any network your phone can reach, and drives the server's full feature set from your phone."*

### Architectural Axioms & House Philosophy
- **Authoritative Server**: *"The server is authoritative. Sessions, config, credentials, permissions and tool execution all live on the server. The app is a thin, event-driven client, the same model the TUI, web app and desktop app use."*
- **Zero Optimistic Guessing**: *"The server echoes; the client never guesses. State changes come from events or REST snapshots. Optimistic UI is limited to inbox items, which the server echoes through `session.inbox.enqueued`."*
- **Build over Prose**: *"The build is the source of truth, not the documents. This repo asserts claims with gates rather than prose, and several documents are parsed by tooling. When docs and a script disagree, the script wins."*
- **What Matters Before What Measures**: Driving the agent, context weight feedback, live permission grant, and reversibility precede secondary analytics.

### 2. What the README Leads With (The Dominant Feature)
The README leads with **active bi-directional control over the agent** rather than passive telemetry or static viewing.

- **Driving over viewing**: *"The app pairs with one or more OpenCode servers, keeps a live event stream to each of them, **drives** them from the phone, stays reachable while it works..."*
- **Context honesty**: *"...says what a prompt is about to carry before it sends it..."*
- **Agency and reversibility**: *"...shows what the agent changed and lets you take it back..."*
- **Full workstation capabilities**: *"...gives you a real terminal, a command panel, the subagent tree and parallel worktrees..."*

### 3. Critical Files & Architecture
The codebase contains **16 Gradle modules** (plus `build-logic`), strictly structured so feature modules depend exclusively on `:core:*` and **never directly on each other**.

- `:app`: Composition root, type-safe navigation graph (`Routes.kt`), hosts (`SessionHost.kt`, `ReviewHost.kt`, `AdminHost.kt`).
- `:feature:sessions`: Live transcript, timeline projection, session list.
- `:feature:composer`: Prompt dock, @-mentions, context payload bar.
- `:feature:requests`: Attention dock, blocking permissions, lockscreen notifications (`ConnectionService.kt`).
- `:feature:review`: Unified/split diffs, line commenting, revert/commit staging.
- `:feature:execution`: PTY terminals (xterm.js), subagents tree, shell processes.
- `:feature:integrations`: Accounts, OAuth, MCP servers, plugins.
- `:feature:admin`: `opencode.jsonc` schema explorer and editor.
- `:feature:insights`: Usage analytics, heatmaps, RPC playground.
- `:core:designsystem`: Developer Terminal Canvas theme, JetBrains Mono typography, `DiffTable`, shared tokens.
- `:core:network`: OkHttp client, `ServerApi` (138 REST operations), SSE parser, `PtySocket`.
- `:core:data`: `TimelineReducer`, `RequestCenter`, `ServerDataSet`.
- `:core:model`: Pure Kotlin data models mirroring the V2 schema.
