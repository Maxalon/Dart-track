# Dart-track

Personal-use Android app for tracking dart games. Native Kotlin + Jetpack
Compose. Three modes (X01 with configurable start, Cricket, Half-It), 1-4
players, local game history and aggregate statistics.

## Game modes

- **X01** — 101, 201, 301, 401, 501, 701 or 901 start. Optional double-out.
  3-dart total entry per turn (numpad with confirm). Bust detection,
  finish-on-double prompt.
- **Cricket** — Standard scoring on 15-20 + bull. Per-target mark counters,
  +/− entry, max 9 marks per turn (3 darts × triple). Win when all targets
  closed and lead/tie on points.
- **Half-It** — 9 fixed rounds (15, 16, any double, 17, 18, any triple,
  19, 20, bullseye). Score 0 in a round halves your total. Numpad entry of
  this round's points.

## Score entry

- Numeric keypad with confirm button (no dart-by-dart entry).
- Backspace and clear for in-progress correction.
- **Undo** button on every game screen reverts the last confirmed turn —
  reusable across multiple turns.

## Local data

- All games (in-progress and finished) are saved to
  `<app-files>/games.json` via `kotlinx.serialization`.
- History screen lists all games, lets you resume in-progress games, view
  details, or delete them.
- Statistics screen aggregates per-player numbers across all games:
  3-dart average, best turn, best checkout (X01), wins, high scores
  (Half-It), etc.

## Releases & APK install

Every push to `main` triggers
[`.github/workflows/release.yml`](.github/workflows/release.yml), which:

1. Computes a version from the commit count on `main`:
   `versionName = 0.1.<count>`, `versionCode = <count>`.
2. Builds `:app:assembleRelease` (signed with the committed personal-use
   keystore).
3. Creates a new git tag `v0.1.<count>` and a GitHub Release with the APK
   attached as `dart-track-0.1.<count>.apk`.

To install on your phone:

1. Open the [Releases page](../../releases) and download the latest APK.
2. On the phone, allow install from this source (Settings → Apps →
   Special access → Install unknown apps).
3. Open the APK to install.

Because the APK is signed with a **stable** keystore committed to the repo
(`app/debug.keystore`), every new release installs over the previous one
without an uninstall step.

> ⚠️ The keystore in `app/debug.keystore` is intentionally committed for
> personal/sideloading use only. Do not publish builds signed with this key
> to any app store.

## Local development

- JDK 17, Android SDK with API 35.
- `./gradlew :app:assembleDebug` for a debug build, or `:app:assembleRelease`
  for the release variant.
- `./gradlew :app:installDebug` to install over ADB.
- The min SDK is 26 (Android 8.0).

The Gradle wrapper is committed; the first build downloads Gradle 8.10.2
and the Android Gradle Plugin 8.7.2.
