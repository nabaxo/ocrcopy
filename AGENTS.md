# OCR Copy (dev.nabaxo.ocrcopy)

Personal Android app: point the camera at text, freeze the frame, select exact
characters, copy. Fully offline. Built for and tested on the owner's Pixel 8 Pro.

Hard requirements (the reason this app exists):
- No system text-selection menu, no web search, no Lens. Our own selection UI;
  the only actions are Copy.
- No network. `INTERNET` / `ACCESS_NETWORK_STATE` are stripped in the manifest, and
  ML Kit's datatransport upload backend (`TransportBackendDiscovery`) is removed.
  Do not add anything that needs the network.

## Stack

Kotlin, Jetpack Compose (Material 3, dynamic color), CameraX, ML Kit text
recognition (bundled Latin model, on-device). Single activity, portrait only.
minSdk 26, target/compile 35, arm64-v8a only, R8 on.

Toolchain: system JDK 25 (Fedora), Gradle 9.1 wrapper, AGP 8.13, Kotlin 2.2.21,
Android SDK at `~/Android/Sdk` (cmdline-tools, no Android Studio).

## Files

- `app/src/main/java/dev/nabaxo/ocrcopy/MainActivity.kt`: everything UI. Camera screen
  (CameraX preview + capture), select screen (frozen photo, zoom/pan, selection
  handles, magnifier, Copy chip, bottom bar), theme, transitions.
- `.../Ocr.kt`: turns ML Kit `Text` into `Scan`: per-character glyphs (tilted quads
  from cornerPoints), line shapes, padding constants, selection shapes, hit-testing.
- `.../Gooey.kt`: the dim layer. Draws line shapes into a mask, blurs, thresholds,
  so text areas get rounded corners and merge where close ("metaball" look).
- `app/src/main/res/`: adaptive launcher icon (light/dark colors + monochrome).
- `art/icon-b.svg`: unused alternative icon design, kept on purpose.

## Build, install, test

```
./gradlew assembleRelease            # -> app/build/outputs/apk/release/app-release.apk
timeout 60 adb install -r app/build/outputs/apk/release/app-release.apk
```

- Run build and install as separate commands. `adb install` can hang (phone locked,
  disconnected, prompt on screen) and would swallow the build result.
- Never pipe gradle or adb into `tail`/`head` for status: the exit code is then tail's.
  Redirect to a log file and check the real exit code.
- There are no automated tests. The owner tests on the phone and reports back,
  often with screenshots. After a visual change: build, install, stop, wait.

## Release signing (secrets: hands off)

Release builds are signed with the owner's keystore. `app/build.gradle.kts` reads
`OCRCOPY_STORE_FILE`, `OCRCOPY_STORE_PASSWORD`, `OCRCOPY_KEY_ALIAS`,
`OCRCOPY_KEY_PASSWORD` from `~/.gradle/gradle.properties` and fails the release
build if any is missing (never falls back to the debug key).

**Never read, write, cat, grep or otherwise open `~/.gradle/gradle.properties` or any
`.jks` file.** Anything an agent reads ends up in the transcript. If signing fails,
tell the owner what is missing and let them fix it.

## Behaviour and tuned values (decided with the owner; change only when asked)

- Capture: full camera resolution, 4:3, `CAPTURE_MODE_MAXIMIZE_QUALITY`. Only scaled
  down if the bitmap would exceed 90 MB (Android's draw limit is 100 MB). Decode and
  rotation run on a background executor.
- Viewfinder and frozen frame use identical layout (status bar, 4:3 FIT_CENTER picture
  area, 176dp bottom panel) so the photo freezes exactly where the preview was.
- Transition: viewfinder fades to black (200ms) with centered "Processing…"; frozen
  frame waits two frames (hides the GPU upload stall), then fades in (300ms).
- ML Kit warm-up on a tiny blank bitmap at launch.
- Line shapes: tight tilted rectangle around each line's character quads (ML Kit's
  line cornerPoints are often short at the start). Padding as fractions of line
  height: bright area 60% along / 30% across the text; highlight 30% / 15%. The
  bright area needs the extra ~30% because the gooey rounding eats its corners.
  A selection end that cuts through a word gets only 10% along (full padding spilled
  over the unselected neighbour; 0% showed ML Kit's box error bare).
- Highlight: one rounded shape per selected line, not merged, `BlendMode.Multiply`
  (highlighter look: paper takes the color, black text stays black).
- Selection: long-press or double-tap selects a word; teardrop handles; Copy chip
  floats above the selection; magnifier (1.5x, 140x72dp, centred 84dp above the aim
  point) while dragging a handle. One-finger drag pans only when zoomed.
- Words: ML Kit words containing a space are split at the space. First/last char of
  words with 3+ chars is trimmed if wider than 1.5x the word's median char width.

## Known limits and rejected approaches (do not retry without a new idea)

- ML Kit's word/character boxes can be off by ~half a letter on tilted text, and
  character boxes are often just even slices of the word box. Confirmed with a debug
  overlay. Padding hides it; there is no clean fix from geometry alone. Even real
  per-symbol boxes jitter ±~1/5 char (logged on "52063492814": even digits, gaps
  alternating ~70/100 px), so a mid-word selection edge can look off. Accepted.
  Idea not taken: find mid-word boundaries from the photo (least-ink column between
  neighbouring chars). Other engines wouldn't help: all give char boxes only as
  estimates from line-level recognition.
- End-character trimming, tried and rejected: height-based limit (missed the cases),
  uppercase exception (worse), pixel scan for the ink edge (counted printed table
  lines as part of the word). The median rule's known flaw: it can clip a capital
  that starts a lowercase word.
- ML Kit sometimes glues words split only by a printed table line into one element
  with no space (seen: "SEK" + number → `SEK52063492814`). Accepted as a known issue.
  Ideas not taken: split at letter↔digit change (breaks `A4`, `MP3`), split at wide
  gaps between char boxes (char boxes often fake).
- Merging the selection highlight like the dim layer: removed on purpose.
- Live OCR boxes on the viewfinder: skipped by choice.
- Open-source OCR (PaddleOCR via ONNX, Tesseract): judged worse or a lot more work.
  Only relevant if the app ever goes to F-Droid (ML Kit is not FOSS).

## Open items

- Distribution: no Play Store. Maybe a signed APK on the owner's Forgejo later.

## Working style

- Small, logical commits; English commit messages ending with the Co-Authored-By
  trailer. Commit only when the owner says so.
- Keep it simple (grug brain): no new abstractions, layers or files unless needed.
