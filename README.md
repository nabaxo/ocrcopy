# OCR Copy

> [!WARNING]
> Note that Github is only a mirror of my own private forge. Anyway, [fuck apartheid](https://bdsmovement.net/news/sign-pledge-boycott-microsoft%E2%80%99s-xbox), this repo is only here out of necessity. ¯\\\_(ツ)\_/¯

Are you also tired of Google Lens doing a fucking google search when selecting some text? This is the app for you! Just point the camera at text, freeze the frame, select exact characters, copy.

Fully offline (on-device ML Kit, no network permission). No search, no Lens, only Copy.

## Build

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release builds need `OCRCOPY_STORE_FILE`, `OCRCOPY_STORE_PASSWORD`, `OCRCOPY_KEY_ALIAS`
and `OCRCOPY_KEY_PASSWORD` in `~/.gradle/gradle.properties`.
