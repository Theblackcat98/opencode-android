# Release runbook

Everything a release needs, and an honest statement of what has not been done.

## What exists and what does not

| | State |
| --- | --- |
| Release signing configured | **Yes** — reads `keystore.properties` or the environment, and attaches the config only when all four values are present |
| A keystore in the repository | **No**, and none was created. There is no signing material anywhere in this tree. |
| A signed artifact | **Not produced.** `assemblePlayRelease` produces `app-play-release-unsigned.apk`, named for what it is. |
| The release tasks gated | **Yes** — they print what is missing rather than producing something that looks finished. |
| The Play Console track progression | **Not run.** No account, no upload. |
| GitHub Releases | **Not published.** No release was cut. |
| The manual test matrix | **Not run.** No device, no emulator, no second device. |
| The Phase 10 screens | **Not built.** The operations are real and tested; nothing puts them on a display. See `docs/CHANGELOG.md`. |
| Version | `0.1.0`, `versionCode` 1 |

**No signed artifact exists.** That is the accurate statement, and nothing in this repository claims
otherwise.

## Building a signed release

1. **Generate the keystore once.** It cannot be recovered if lost, so store it where the owner's
   secrets live and not in this repository.

   ```bash
   keytool -genkeypair -v \
     -keystore opencode-release.jks \
     -alias opencode \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

   Validity beyond the Play requirement: the key signs every future update, and a key that expires
   means an app nobody can update.

2. **Put the four values in `keystore.properties`**, at the repository root. It is in `.gitignore`
   and must stay there.

   ```properties
   storeFile=/absolute/path/to/opencode-release.jks
   storePassword=…
   keyAlias=opencode
   keyPassword=…
   ```

   For CI, use the environment instead — the same names, so the code path is identical:

   ```
   OPENCODE_KEYSTORE, OPENCODE_STORE_PASSWORD, OPENCODE_KEY_ALIAS, OPENCODE_KEY_PASSWORD
   ```

3. **Build.**

   ```bash
   ./gradlew clean assemblePlayRelease assembleFdroidRelease
   ```

   The `releaseMaterialMissing` task prints what it wanted and succeeds, so a build without the
   material is still a useful compile check. With the material present the two APKs are signed:

   ```
   app/build/outputs/apk/play/release/app-play-release.apk
   app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
   ```

   Note the name difference: with no material the output is `-unsigned.apk`, and that difference is
   the whole safety mechanism. An unsigned artifact cannot be updated in place, so publishing one and
   then signing a later build with the real key gives users two apps with the same id and no way to
   move between them.

4. **Verify the signature before uploading anything.**

   ```bash
   $ANDROID_HOME/build-tools/*/apksigner verify --print-certs app-play-release.apk
   ```

5. **Run the manual test matrix** ([`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md)) on a signed
   build. Not on a debug build: the debug build has a different application id and different
   behaviour in a few places.

6. **Publish**, in this order.

   | Track | Who | Purpose |
   | --- | --- | --- |
   | Internal | The team | Confirms the artifact installs and the server talks to it. A handful of testers. |
   | Closed | A defined list | Confirms it against real hardware and real servers. Weeks, not days. |
   | Production | Everyone | Only after closed has run without a blocking defect. |

   A Play release is a *track* before it is a rollout. Internal is not "production with fewer
   people"; it is the stage where a packaging mistake is cheap.

7. **Cut the GitHub release** with both APKs, their mapping files, and the SHA-256 of each. The
   F-Droid flavor is the one to attach for anyone who wants no Google services at all.

## ProGuard

Minification and resource shrinking are on for release. Three things in this app need a keep rule
and the release is how you find out:

- **kotlinx.serialization.** Generated serializers are reached reflectively. `proguard-rules.pro`
  in `app/` has to keep them; a release build that decodes nothing at runtime is the classic symptom.
- **The event union.** `EventTypes` holds `KSerializer` instances built by hand, so the payloads
  need keeping by name.
- **Room.** Schema entities are constructed reflectively.

**Not verified here:** R8 ran and produced an APK of about 25 MB, but nothing in that APK was
executed, because there is no device. **Run the manual test matrix on a minified build, not only on
a debug one.** A release-only crash from a stripped serializer is a common and expensive surprise.

## F-Droid specifics

- The flavor has its own application id (`.fdroid`) and its own version suffix, so both builds can
  be installed side by side and a Play install is never mistaken for the F-Droid one.
- It uses ZXing for QR scanning rather than ML Kit, and ships no Google Play Services dependency.
  Verify on a device: `adb shell pm list packages | grep gms` should be empty.
- F-Droid builds are reproducible from the tag and the recipe, and are signed by F-Droid's own key,
  not by the release key. Do not upload a Play-signed artifact to F-Droid.

## What a release still needs that this phase could not do

1. Run the manual test matrix and record the results. **This is the open exit criterion.**
2. Produce a signed artifact from real signing material.
3. Run the Play Console track progression: internal, then closed, then production.
4. Publish the GitHub release.
5. Build the Phase 10 screens the phase plan lists and this runbook does not have: the insights
   dashboard, the RPC console, the session-log viewer, the widget, the Quick Settings tile, app
   shortcuts, the command palette and leader keys, session tabs, the adaptive list-detail layouts, the
   LAN prober and the OpenCode theme import. Their data layers are built and tested, so each is a
   screen over something that already works.
6. Fix accessibility finding **A-1** from the audit — a live region on the turn boundary — before
   the closed track, since it is the difference between the app being usable and not with a screen
   reader.
7. Re-read the security review's *What a release still has to do* and the privacy review's, and
   record the decisions. Both name an owner decision that has not been taken: whether the keystore
   key should require user authentication, and whether the unencrypted message cache stays as it is.
