# Manual release checklist — the part no agent may do

Automation covers what it can (`docs/TEST_AUTOMATION.md`). Everything below needs your hands,
your devices, or your signing keys. Work it top to bottom; the matrix (`docs/MANUAL_TEST_MATRIX.md`)
is not passable until every row has a result, and **a release is not ready while any row is blank**.

## 0. One-time setup (before the first run)

- [ ] Pick the physical devices for A1–A9: smallest supported phone (API 26), a mid-range phone,
      a current flagship, a small tablet, a tablet, a foldable, a ChromeOS device, plus one phone
      with no Google apps for the F-Droid leg (A9/L5).
- [ ] Enable the new workflow: push this branch (or merge it) so `.github/workflows/instrumented-tests.yml`
      runs on KVM-capable CI. Confirm all five jobs go green before you trust any automated row.
- [ ] Keep your signing material yours: the release keystore is created by you, stored by you,
      never in the repo, never handled by an agent.

## 1. Human-judgment passes (no automation possible)

- [ ] **K1–K6 accessibility** on A1 and A3: TalkBack on, font size at platform maximum. Every
      control named, timeline navigable, permission requests announced, nothing clipped, focus
      order sane, 4.5:1 contrast including the code palette. Accessibility finding A-1 is still
      open — verify it specifically.
- [ ] **C4 scrolling** on the mid-range phone (A2): a long session must not drop frames.
- [ ] **F2/F3 terminal quality**: `htop`/`vim` render correctly, rotation and backgrounding
      reattach at the right cursor.

## 2. Two-client runs (needs a second device or a TUI)

- [ ] **B9**: "Pair another device" shows a QR; the second device redeems it.
- [ ] **B1 camera half**: scan the real QR from `opencode pair` with the phone camera (the
      redemption path itself is automated).
- [ ] **I1–I8**: connect a TUI to the same server as the second client and work the whole
      section — toasts, follow-desktop on/off, offered-not-performed commands, plugin `rpc.*` events.

## 3. Real-device trust and network behavior

- [ ] **B5 by hand, once**: install the CA through Settings (not the test script) and confirm the
      trust toggle connects. If the automated B5 ever fails on the `/data/misc/keychain/cacerts-added`
      read, this is where you find out whether the product or the test is wrong.
- [ ] **B6 by hand, once**: no CA, toggle off — refusal must name the certificate problem.
- [ ] **D8**: drop the network mid-turn (airplane mode), reconnect, confirm resync.
- [ ] **G1**: OAuth login against each real provider, in a real browser.

## 4. Form factors the emulator only approximates

- [ ] **A1–A8 on hardware**: install matrix, with the fdroid APK on the no-GMS phone (A9).
- [ ] **J1–J3**: list/detail side by side on tablet landscape and unfolded foldable; rotation and
      window resize reflow without losing scroll.
- [ ] **F4**: resizing the terminal on the foldable/tablet resizes the PTY.

## 5. Things that don't exist yet — verify before release

These matrix rows reference product UI that is **not in the source** (checked 2026-09-29). Either
they get built and tested, or the matrix rows are explicitly marked `n/a` with a note — a blank
row blocks the release either way.

- [ ] B7/B8 — LAN prober
- [ ] J4/J5/J6 — command palette
- [ ] J8 — widget (also H5)
- [ ] J9 — Quick Settings tile

## 6. Signing and publishing (all you)

- [ ] **M1/M2**: sign the Play and F-Droid APKs; the Play APK installs over the previous version.
- [ ] **M3**: both flavors installed side by side (distinct application IDs).
- [ ] **M6**: Play internal track → closed → production, in order.
- [ ] **M7**: GitHub Releases carries both APKs, the mapping files, and SHA-256 hashes.
- [ ] **M5**: the published compatibility matrix matches what you actually ran — oldest/latest
      server versions (L1/L2) included.

## 7. Record it

- [ ] Fill the *Result* column of `docs/MANUAL_TEST_MATRIX.md`: `pass`, `fail`, or `n/a` with a
      note per row. For a failure, the note is the symptom and the device, not the diagnosis.
- [ ] Re-run `node tools/audit-coverage.mjs --strict` — it should agree the matrix has no blanks.
