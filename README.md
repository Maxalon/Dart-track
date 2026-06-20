# Dart-track

A native Android darts tracker built in Kotlin + Jetpack Compose. Players are
**registered once** (a stable id plus a unique display name) and then **picked
per seat** when starting a game, so every game and every statistic is tied to a
real player rather than a free-typed name. Seven game modes — competitive and
practice — local game history, deep per-player statistics, and an optional
voice caller. Fully offline, no ads, no accounts.

## Game modes

Seven modes, choosable from the New Game screen. X01, Cricket and Half-It are
the competitive games; the rest form a **practice suite**.

- **X01** — start from 101, 201, 301, 401, 501, 701 or 901, with optional
  double-out. You enter a single 0-180 three-dart total per turn (numpad with
  confirm); individual darts aren't tracked. Bust detection, finish-on-double
  prompt, and **live checkout suggestions** when the remaining score is
  finishable. **Match play**: play a single leg, first-to-N legs (1/3/5/7), and
  optionally first-to-N sets (1/3/5). The throw order rotates each leg, a
  scoreboard shows legs/sets won, and Undo works across leg and set boundaries.
  Stats aggregate over every leg of a match.
- **Cricket** — standard scoring on 20, 19, 18, 17, 16, 15 and bull. Per-target
  mark counters with +/− entry, up to 9 marks per turn (3 darts × triple). Win
  when you've closed all targets and your score leads or ties every opponent.
- **Half-It** — 9 fixed rounds (15, 16, any double, 17, 18, any triple, 19, 20,
  bullseye). You enter the points scored on each round's target; score 0 in a
  round and your running total is halved (rounded down). Highest total wins.

### Practice suite

- **Around the Clock** — race targets 1→20 in order. Each turn you enter how
  many of your next consecutive targets you cleared (0-3); first to clear 20
  wins. Darts thrown are tracked so you can chase a personal best.
- **Bob's 27** — doubles practice. Everyone starts on 27 and works the doubles
  of 1→20 in order. Each turn, hits on the round's double add `hits × (2 × N)`;
  a miss (0 hits) subtracts `2 × N`. Drop to 0 or below and you're out. Highest
  score after double 20 wins.
- **Shanghai** — 7 rounds targeting numbers 1→7. Each turn you record singles,
  doubles and triples of the round number; points are `(s + 2d + 3t) × round`.
  Hitting a single, double and triple of the number in one turn is an instant
  "Shanghai" win. Otherwise the highest total after round 7 wins.
- **Catch 40** — doubles ladder practice. Each player works their own ladder
  down from D20 (value 40) to D1. Catch your current double (≥1 hit) to score
  its value and drop to the next double; miss and you stay. Catching D1 finishes
  your ladder. Highest score wins (turns are capped to bound the game).

## Players & stats

- **Player registry** — players are stored in a local registry (`players.json`)
  with a stable UUID and a name that's unique case-insensitively. Adding a
  player is "get-or-create": typing an existing name reuses that player rather
  than duplicating it.
- **Per-seat picker (required)** — the New Game screen gives each of the 1-4
  seats a searchable picker over registered players (you can also create a new
  player inline). A seat can't repeat a player already chosen in another seat,
  and a game can't start until every seat is filled.
- **Per-player stats screen** — pick a player to see their numbers across all
  games: games played / won / win %, and for X01 a deep breakdown — 3-dart
  average, first-9 average, checkout %, 180s and ton+ counts (100+/140+), best
  leg (fewest darts) and average darts per leg, legs / sets / matches won, and a
  **score-band (per-visit) distribution**. Other modes get a per-mode summary
  (games, wins, and a mode-appropriate best). The screen surfaces additional
  views — such as a 3-dart-average **trend over time** and **head-to-head**
  comparisons — as they become available.

> X01 stats are computed from the per-turn 3-dart totals the app stores (it
> never records individual darts), so checkout % and the doubles-based figures
> are turn-level approximations meant for tracking trends, not PDC-precise
> percentages.

## Score entry & UX

- **Modern Material 3** design: a dartboard-red light/dark color scheme with
  **dynamic color** (Material You) on Android 12+, plus a dark theme.
- **Single-screen scoreboards**: every mode shares a consistent layout with the
  **active player as the hero** (large current score/target with a count-up
  animation), a bold current-player indicator, and — for X01 — a prominent
  checkout-suggestion chip, built for reading at arm's length off a stand.
- **Large keypad entry** with confirm, backspace and clear; no dart-by-dart
  entry. An **Undo** button on every game screen reverts the last confirmed
  turn, reusable across multiple turns.
- **Voice caller (optional)** — a speaker toggle reads scores and "Game shot!"
  aloud via on-device text-to-speech. Off by default; fully offline.
- **Animated navigation**: slide + fade transitions between screens (New Game
  enters as a slide-up modal).

## Local data

- All games (in-progress and finished) are saved to `<app-files>/games.json`,
  and the player registry to `players.json`, via `kotlinx.serialization`.
- The **history** screen lists every game, lets you resume in-progress games,
  view details, or delete them.
- **Export / import** — the history screen can export all games as a JSON file
  via the Android share sheet (same on-disk format as `games.json`) and import
  that file back (merge by id, idempotent) for backup or moving devices.

## Build & CI

- **Build check** — [`.github/workflows/build-check.yml`](.github/workflows/build-check.yml)
  runs on every pull request (and on pushes to `sprint-*` branches): it runs the
  JVM unit tests and assembles a debug APK as a compile gate. It never creates a
  release.
- **Release** — every push to `main` triggers
  [`.github/workflows/release.yml`](.github/workflows/release.yml), which:
  1. computes a version from the commit count on `main`
     (`versionName = 0.1.<count>`, `versionCode = <count>`);
  2. builds `:app:assembleRelease` (signed with the committed personal-use
     keystore);
  3. creates a git tag `v0.1.<count>` and a GitHub Release with the APK attached
     as `dart-track-0.1.<count>.apk`.

Unit tests live under `app/src/test/` and cover the game-mode rules (X01
bust/finish/undo and stat math, Cricket scoring/win conditions, Half-It halving
and the practice modes), the player registry, and JSON persistence round-trips.
Run them with `./gradlew :app:testDebugUnitTest`.

## APK install

1. Open the [Releases page](../../releases) and download the latest APK.
2. On the phone, allow install from this source (Settings → Apps → Special
   access → Install unknown apps).
3. Open the APK to install.

Because the APK is signed with a **stable** keystore committed to the repo
(`app/debug.keystore`), every new release installs over the previous one without
an uninstall step.

> ⚠️ The keystore in `app/debug.keystore` is intentionally committed for
> personal/sideloading use only. Do not publish builds signed with this key to
> any app store.

## Local development

- JDK 17, Android SDK with API 35; min SDK 26 (Android 8.0).
- `./gradlew :app:assembleDebug` for a debug build, or `:app:assembleRelease`
  for the release variant.
- `./gradlew :app:installDebug` to install over ADB.

The Gradle wrapper is committed; the first build downloads Gradle 8.10.2 and the
Android Gradle Plugin 8.7.2.
</content>
</invoke>
