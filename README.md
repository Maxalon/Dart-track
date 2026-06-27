# Dart-track

A native Android darts tracker built in Kotlin + Jetpack Compose. Players are
**registered once** (a stable id plus a unique display name) and then **picked
per seat** when starting a game, so every game and every statistic is tied to a
real player rather than a free-typed name. Fourteen game modes — competitive and
practice — an optional **CPU opponent**, local game history, deep per-player
statistics, **achievements**, cross-player **leaderboards**, app **settings**,
and an optional voice caller. Fully offline, no ads, no accounts.

## Game modes

Fourteen modes, choosable from the New Game screen. X01, Cricket, Half-It,
Gotcha and Killer are the competitive games; the rest form a **practice suite**.

- **X01** — start from 101, 201, 301, 401, 501, 701 or 901, with optional
  double-out. You enter a single 0-180 three-dart total per turn (numpad with
  confirm); individual darts aren't tracked. Bust detection, finish-on-double
  prompt, and **live checkout suggestions** when the remaining score is
  finishable. **Match play**: play a single leg, first-to-N legs (1/3/5/7), and
  optionally first-to-N sets (1/3/5). The throw order rotates each leg, a
  scoreboard shows legs/sets won, and Undo works across leg and set boundaries.
  Stats aggregate over every leg of a match. A seat can be a **CPU opponent**
  (see below).
- **Cricket** — standard scoring on 20, 19, 18, 17, 16, 15 and bull. Per-target
  mark counters with +/− entry, up to 9 marks per turn (3 darts × triple). Win
  when you've closed all targets and your score leads or ties every opponent.
  An optional **Cut-Throat** variant (a setup toggle): once you've closed a
  target, your extra marks on it are charged as points **against** every
  opponent who hasn't closed it yet, and the **lowest** score wins — turning the
  scoring game into a defensive one.
- **Half-It** — 9 fixed rounds (15, 16, any double, 17, 18, any triple, 19, 20,
  bullseye). You enter the points scored on each round's target; score 0 in a
  round and your running total is halved (rounded down). Highest total wins.
- **Gotcha** — a race to land on **exactly** 301 or 501. You enter a 0-180
  three-dart total each turn; overshooting the target **busts** (the turn scores
  nothing and you stay put), and hitting it exactly wins instantly. The twist:
  end a turn on the **same total** an opponent is currently sitting on (above 0)
  and you knock them all the way back to 0 — one turn can reset several rivals.
- **Killer** — a 2-4 player knockout. Each seat is assigned a distinct board
  number (seat `i` gets `20 - i`, so 20, 19, 18, 17). You first **arm** yourself
  by hitting the **double of your own number**, then drain opponents' lives by
  hitting **their** doubles; once armed, hitting your own double again is a
  **self-kill** that costs you a life. Everyone starts with 3 lives (5 selectable
  at setup); reach 0 and you're eliminated. Last player standing wins — but a
  single visit that wipes out everyone still in it (an armed player draining the
  last opponent as their own last life goes) is a **draw**. Entry is
  Cricket-style per-target taps, not dart-by-dart numeric entry. Human-only — not
  available to the CPU opponent.

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
- **Bermuda** (a.k.a. Treasure Island) — 12 rounds on a fixed target ladder (12,
  13, 14, any Double, 15, 16, 17, any Triple, 18, 19, 20, Bull). Each round you
  enter the points scored on that round's target; score 0 and — like Half-It —
  your running total is **halved** (rounded down). Highest total after all 12
  targets wins.
- **Catch 40** — doubles ladder practice. Each player works their own ladder
  down from D20 (value 40) to D1. Catch your current double (≥1 hit) to score
  its value and drop to the next double; miss and you stay. Catching D1 finishes
  your ladder. Highest score wins (turns are capped to bound the game).
- **Count-Up** — 8-round high-total practice. Like X01 you enter a single 0-180
  three-dart total per turn, but there is no target to chase down: each total
  simply adds to your running score. After 8 rounds the highest cumulative total
  wins (ties allowed). A seat can be a **CPU opponent** (see below).
- **Checkout Trainer** — a finishing drill over a ladder of double-out checkouts
  (40, 60, 80, 100, 120, 140, 160, 170). Everyone attempts the same target in
  turn before the ladder advances. Each attempt is one visit of up to 3 darts;
  you record **Hit** (with the number of darts used, 1–3) or **Miss**. Your
  score is the number of checkouts hit, and ties are broken by the **fewest
  darts** used on those hits — so the more efficient finisher wins. The suggested
  finishing route is shown for the current target.
- **Baseball** — 9 innings on the numbers 1→9 (inning N targets number N). Each
  turn you record how many singles, doubles and triples of the inning's number
  you hit; the runs added are `s + 2d + 3t` (no inning multiplier, no instant
  win — unlike Shanghai). Highest total after 9 innings wins.
- **Golf** — 9 holes, hole N played on the number N, scored like stroke-play
  golf where **lower is better**. You throw up to 3 darts at the number and
  record your **best** dart: triple = 1 stroke, double = 2, single = 3, a miss =
  5. Lowest total strokes after 9 holes wins.

### CPU opponent

In **X01** and **Count-Up**, any seat on the New Game screen can be made a
**CPU** instead of a registered player, at one of four difficulties — **Easy**,
**Medium**, **Hard** or **Pro** — anchored to real 3-dart averages (roughly 40 /
60 / 80 / 100). You can mix humans and CPUs in the same game (at least one human
seat is required). When it's a CPU's turn the app plays it automatically after a
short pause; the bot's visit goes through the exact same scoring, persistence and
undo path a human turn does, so its rules are identical. On a finish the bot
attempts the checkout with a skill-scaled success rate.

The opponent is a small, **fully offline, on-device** statistical model: each
visit is built dart-by-dart from real board segments (so every total it produces
is a legal 3-dart score), with no network, accounts or ads involved.

## Players & stats

- **Player registry** — players are stored in a local registry (`players.json`)
  with a stable UUID and a name that's unique case-insensitively. Adding a
  player is "get-or-create": typing an existing name reuses that player rather
  than duplicating it.
- **Per-seat picker (required)** — the New Game screen gives each of the 1-4
  seats a searchable picker over registered players (you can also create a new
  player inline). A seat can't repeat a player already chosen in another seat,
  and a game can't start until every seat is filled. In X01 and Count-Up a seat
  can instead be a **CPU** opponent; a game still needs at least one human seat.
- **Per-player stats screen** — pick a player to see their numbers across all
  games: games played / won / win %, and for X01 a deep breakdown — 3-dart
  average, first-9 average, checkout %, 180s and ton+ counts (100+/140+), best
  leg (fewest darts) and average darts per leg, legs / sets / matches won, and a
  **score-band (per-visit) distribution**. Other modes get a per-mode summary
  (games, wins, and a mode-appropriate best). The screen surfaces additional
  views — such as a 3-dart-average **trend over time** and **head-to-head**
  comparisons — as they become available, and links to that player's
  **achievements** (below).
- **Achievements** — a board of 31 milestones, computed purely from a player's
  game history and reached from the Player Stats screen. They span overall
  dedication (play 10 / 50 / 100 games, win 25, win 3 in a row, win in / play
  every mode), X01 feats (a 100+ visit, a maximum 180, a 100+ or the 170
  "Big Fish" checkout, an 18-dart-or-fewer leg), CPU-opponent scalps, and
  per-mode feats — an instant Shanghai win, an Around the Clock win, "Marksman"
  (win Cricket), "Half Measures" (win Half-It), "Last One Standing" / the
  flawless "Untouchable" (win Killer, the latter without losing a life),
  "Treasure Island" / "Treasure Hunter" (win / score 250+ in Bermuda), and
  "Full House" (play all 14 modes) — grouped by category with progress bars for
  the tiered ones.
- **Leaderboards** — cross-player rankings reached from Home, one card per
  category: most wins, most games, win %, best X01 3-dart average, most 180s,
  best checkout %, and fewest darts to finish a leg. Each cell is read straight
  from the same per-player stats above, so a leaderboard can never disagree with
  a player's own numbers; the X01-only boards only rank players who have the
  relevant data.

> X01 stats are computed from the per-turn 3-dart totals the app stores (it
> never records individual darts), so checkout % and the doubles-based figures
> are turn-level approximations meant for tracking trends, not PDC-precise
> percentages.

## Score entry & UX

- **Modern Material 3** design: a dartboard-red light/dark color scheme with
  optional **dynamic color** (Material You) on Android 12+. The theme follows the
  system by default, or can be forced to Light or Dark from **Settings**.
- **Single-screen scoreboards**: every mode shares a consistent layout with the
  **active player as the hero** (large current score/target with a count-up
  animation), a bold current-player indicator, and — for X01 — a prominent
  checkout-suggestion chip, built for reading at arm's length off a stand.
- **Large keypad entry** with confirm, backspace and clear; no dart-by-dart
  entry. An **Undo** button on every game screen reverts the last confirmed
  turn, reusable across multiple turns.
- **Voice caller (optional)** — a speaker toggle reads scores and "Game shot!"
  aloud via on-device text-to-speech. Seeds from its saved default; fully
  offline.
- **Sound & haptic feedback** — every applied turn produces a short,
  procedurally-synthesized sound effect and a haptic, with distinct cues for
  scoring, busts and wins. Both are independently off-switchable in **Settings**,
  and are fully offline with no audio assets (all tones generated in code).
- **Animated navigation**: slide + fade transitions between screens (New Game
  enters as a slide-up modal).

## Settings

Reached from the gear on the Home screen, persisted to `<app-files>/settings.json`
(a corrupt or older file degrades cleanly to defaults). Every preference is read
by the screen that consumes it, so what you set here takes effect.

- **Applied live:**
  - **Theme** — System / Light / Dark, applied app-wide.
  - **Material You** — toggles dynamic color on Android 12+ (no effect below).
  - **Keep screen on** — holds the screen awake while the app is in front.
  - **Confirm before delete** — when on, deleting a game from its detail screen
    asks first; when off, it deletes immediately.
  - **Sound effects** and **haptics** — gate the procedural audio/haptic
    feedback played on every applied turn in every mode (including the CPU's
    auto-played visits). The sound is **fully offline and on-device with no audio
    assets** — every tone is synthesized in code — and degrades silently to a
    no-op on a device with no audio output or vibrator. Notable cues: a confirm
    click, a bust buzz (X01), a big-score chime (an X01 180), leg / match / game
    win fanfares, and a life-lost cue (Killer).
  - **Voice caller default** — the in-game caller toggle seeds from this saved
    default (the caller itself still starts whatever this default says, and can
    be toggled per game).
  - **X01 defaults** — the start score and double-out used to seed the New Game
    screen, which reads them when you set up an X01 game.

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

Unit tests live under `app/src/test/` — roughly **567** JVM tests — and cover
the game-mode rules of every mode (X01 bust/finish/undo and stat math, Cricket
scoring/win conditions including Cut-Throat, Half-It halving, Killer's
arm/drain/self-kill knockout and draw logic, Bermuda's target ladder and
halving, Count-Up, Checkout Trainer, Baseball, Golf, Gotcha and the rest of the
practice suite), the **CPU opponent** (its calibration and X01/Count-Up
integration), the **sound/haptic feedback** module (its event mapping and
procedural tone specs), **achievements**, **leaderboards & records**,
**settings** encode/decode, the player registry, and JSON persistence
round-trips. Run them with `./gradlew :app:testDebugUnitTest`.

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
