package com.dartrack.ui.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "How to play" — a static, scrollable reference for every game mode plus a short
 * tour of the app's features. Pure text: it reads no repositories and holds no
 * state, so it mirrors the sibling list screens (Leaderboards / Player stats)
 * structurally — a header [Row] with a Back [TextButton], then a
 * [verticalScroll] column of [Card]s — without the data plumbing.
 *
 * Sections group the twelve modes the same way the New Game screen and README do
 * (Competitive vs. the practice suite), with each mode a card whose title is the
 * mode name and whose body is a short, rules-accurate blurb sourced from the
 * model classes. A final "More" section summarises the surrounding features.
 */
@Composable
fun HowToPlayScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "How to play",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            IntroCard()

            SectionHeader("Competitive")
            ModeCard(
                "X01",
                "Race a starting score (101 up to 901) down to exactly zero. Enter a " +
                    "single 0–180 three-dart total each turn; going below zero busts " +
                    "and scores nothing. With double-out on you must finish on a double, " +
                    "and a live checkout suggestion appears once the score is finishable. " +
                    "Play a single leg or a match — first to N legs, optionally first " +
                    "to N sets — and a seat can be a CPU opponent.",
            )
            ModeCard(
                "Cricket",
                "Close the numbers 20 down to 15 and the bull by scoring three marks on " +
                    "each (single = 1, double = 2, triple = 3). Once you have closed a " +
                    "number, extra marks on it score its value while any opponent is still " +
                    "open. Close everything with the lead and you win. The optional " +
                    "Cut-Throat variant flips this: your extra marks are charged against " +
                    "opponents instead, and the lowest score wins.",
            )
            ModeCard(
                "Half-It",
                "Nine fixed rounds — 15, 16, any double, 17, 18, any triple, 19, 20 " +
                    "and bullseye. Each round you enter the points you scored on that " +
                    "target. Miss it completely (score 0) and your running total is halved, " +
                    "rounded down, so consistency matters. Highest total after the last " +
                    "round wins.",
            )
            ModeCard(
                "Gotcha",
                "A race to land on exactly 301 or 501. Enter a 0–180 three-dart total " +
                    "each turn; overshooting the target busts (you score nothing and stay " +
                    "put) and hitting it exactly wins instantly. The twist: finish a turn " +
                    "on the same total an opponent is sitting on and you knock them all the " +
                    "way back to zero — one turn can reset several rivals.",
            )

            SectionHeader("Practice")
            ModeCard(
                "Around the Clock",
                "Work through the targets 1 to 20 in order. Each turn you enter how many of " +
                    "your next consecutive targets you cleared with your three darts (0–3). " +
                    "First to clear 20 wins, and darts thrown are tracked so you can chase a " +
                    "personal best.",
            )
            ModeCard(
                "Bob's 27",
                "Doubles practice. Everyone starts on 27 and works the doubles of 1 to 20 in " +
                    "order. Each turn, hits on the round's double add hits × (2 × N); " +
                    "a complete miss subtracts 2 × N. Drop to zero or below and you are " +
                    "out. Highest score after double 20 wins.",
            )
            ModeCard(
                "Shanghai",
                "Seven rounds on the numbers 1 to 7. Each turn you record the singles, " +
                    "doubles and triples you hit on the round's number, scoring " +
                    "(s + 2d + 3t) × round. Hit a single, double and triple of the number " +
                    "in one turn for an instant \"Shanghai\" win; otherwise the highest total " +
                    "after round 7 wins.",
            )
            ModeCard(
                "Catch 40",
                "A doubles ladder. Each player works their own ladder down from D20 (value 40) " +
                    "to D1. Catch your current double (at least one hit) to score its value and " +
                    "drop to the next one; miss and you stay. Catching D1 finishes your ladder, " +
                    "and the highest score wins.",
            )
            ModeCard(
                "Count-Up",
                "Eight rounds of pure high-total scoring. Like X01 you enter a single 0–180 " +
                    "three-dart total per turn, but there is nothing to chase down — each " +
                    "total simply adds to your running score. Highest cumulative total after " +
                    "eight rounds wins, and a seat can be a CPU opponent.",
            )
            ModeCard(
                "Checkout Trainer",
                "A finishing drill over a ladder of double-out checkouts (40, 60, 80, 100, 120, " +
                    "140, 160, 170). Everyone attempts the same target before the ladder " +
                    "advances; each attempt you record a Hit (with the number of darts used, " +
                    "1–3) or a Miss. Your score is checkouts hit, ties broken by the fewest " +
                    "darts used — and the suggested finishing route is shown for each target.",
            )
            ModeCard(
                "Baseball",
                "Nine innings on the numbers 1 to 9 (inning N targets number N). Each turn you " +
                    "record the singles, doubles and triples you hit on the inning's number, " +
                    "adding s + 2d + 3t runs — no multiplier and no instant win, unlike " +
                    "Shanghai. Highest total after nine innings wins.",
            )
            ModeCard(
                "Golf",
                "Nine holes scored like stroke-play golf, where lower is better. Hole N is " +
                    "played on the number N; throw up to three darts and record your best dart " +
                    "— triple = 1 stroke, double = 2, single = 3, a miss = 5. Lowest total " +
                    "strokes after nine holes wins.",
            )
            ModeCard(
                "Killer",
                "2-4 players. Each player is given a board number (shown on screen). First hit " +
                    "the DOUBLE of YOUR number to become a 'killer'. Once armed, hitting the " +
                    "double of an opponent's number knocks off one of their lives. Careful: once " +
                    "you're a killer, hitting your OWN double again costs you a life! Lose all " +
                    "your lives and you're out — last player standing wins. Each turn, tap the " +
                    "double you hit for each of your (up to 3) darts.",
            )

            SectionHeader("More")
            ModeCard(
                "CPU opponents",
                "In X01 and Count-Up, any seat can be a CPU instead of a registered player, at " +
                    "Easy, Medium, Hard or Pro difficulty (anchored to roughly 40 / 60 / 80 / 100 " +
                    "three-dart averages). Mix humans and CPUs freely — a game just needs at " +
                    "least one human seat. The bot plays automatically and scores through the exact " +
                    "same path you do.",
            )
            ModeCard(
                "Stats & achievements",
                "Players are registered once and picked per seat, so every game ties to a real " +
                    "player. The Player stats screen shows games, wins and win %, a deep X01 " +
                    "breakdown (averages, checkout %, 180s, best leg and more), and a per-mode " +
                    "summary for the rest, plus a board of achievement milestones earned from your " +
                    "game history.",
            )
            ModeCard(
                "Leaderboards",
                "Cross-player rankings reached from Home, with one card per category — most " +
                    "wins, most games, win %, best X01 three-dart average, most 180s, best " +
                    "checkout %, and the fewest darts to finish a leg. Every cell is read straight " +
                    "from the same per-player stats, so a board can never disagree with a player's " +
                    "own numbers.",
            )
            ModeCard(
                "Settings & voice caller",
                "From the gear on the Home screen you can force the theme to System, Light or Dark, " +
                    "toggle Material You dynamic color, and keep the screen on while you play. An " +
                    "optional voice caller reads scores and \"Game shot!\" aloud via on-device " +
                    "text-to-speech. Everything is fully offline — no accounts, no ads.",
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IntroCard() {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Welcome to Dart-track",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Twelve game modes, an optional CPU opponent, deep per-player stats and " +
                    "leaderboards — all fully offline. Start from New game, pick a player " +
                    "for each seat, and enter your scores on the large keypad. Every screen has " +
                    "an Undo button, and your games are saved automatically. The guides below " +
                    "cover how each mode is played.",
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun ModeCard(name: String, blurb: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                name,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(blurb)
        }
    }
}
