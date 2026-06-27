package com.dartrack.data

import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BaseballState
import com.dartrack.model.BermudaState
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CountUpState
import com.dartrack.model.CricketState
import com.dartrack.model.GOLF_HOLES
import com.dartrack.model.GameMode
import com.dartrack.model.GolfState
import com.dartrack.model.GotchaState
import com.dartrack.model.HalfItState
import com.dartrack.model.KillerState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import com.dartrack.model.X01Stats
import com.dartrack.model.bot.BotLevel

/**
 * Achievements / milestones, computed purely from stored game history. This is
 * the id-based counterpart in spirit to [playerStats]: a player's seat in a game
 * is found by matching `state.players[i].id == playerId`, and games where the
 * player has no seat (no matching id) are skipped entirely. A blank [playerId]
 * never matches any seat (we never key on a blank id), so every achievement is
 * reported locked.
 *
 * All logic here is pure and Android-free so it can be unit-tested without a
 * Context or any file IO. Every achievement is derivable from data already
 * persisted in [GameRecord] / the [com.dartrack.model.GameState] subtypes.
 *
 * Forward-compatibility note: state inspection uses SAFE casts (`as?`) rather
 * than an exhaustive `when`, so introducing a new [com.dartrack.model.GameState]
 * subtype can never break compilation here. A new mode simply doesn't contribute
 * to the X01/Shanghai/Around-the-Clock-specific milestones until taught to.
 */

/** A single achievement definition (static catalog metadata). */
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    /** Display grouping: "Milestones", "X01", "Practice", "Dedication", "Modes", "CPU". */
    val category: String,
)

/**
 * Computed unlock state of one [Achievement] for a specific player.
 *
 * [progress] / [target] describe countable or tiered achievements (e.g. games
 * played toward a tier); [progress] is capped at [target] for display so a
 * player past the bar never shows more than 100%. For a simple one-shot
 * achievement both are 1 (target) and progress is 0 or 1. [unlockedAtMs] is the
 * [GameRecord.createdAtEpochMs] of the EARLIEST game that qualified the player,
 * when that game is determinable; otherwise null (including while still locked).
 */
data class AchievementStatus(
    val achievement: Achievement,
    val unlocked: Boolean,
    val progress: Int,
    val target: Int,
    val unlockedAtMs: Long?,
)

/** Headline count of how many of the [total] catalog achievements are unlocked. */
data class AchievementSummary(
    val unlocked: Int,
    val total: Int,
)

/**
 * Static catalog of every achievement, in display order. [achievementsFor]
 * returns one [AchievementStatus] per entry, in this exact order.
 */
object AchievementCatalog {

    // ---- Tunable thresholds for the new-mode / breadth achievements. Exposed as
    // named constants so the catalog description text and [achievementsFor]
    // detection logic can never drift apart. ----
    /** Golf par: a stroke total strictly below this finishes "under par". */
    const val GOLF_PAR: Int = 3 * GOLF_HOLES
    /** Total runs that qualify a Baseball game for the slugger feat. */
    const val BASEBALL_SLUGGER_RUNS: Int = 30
    /** Cumulative total that qualifies a Count-Up game for the high-score feat. */
    const val COUNTUP_HIGH_TOTAL: Int = 400
    /** Distinct modes that must be PLAYED to unlock [MODE_EXPLORER]. */
    const val MODE_EXPLORER_TARGET: Int = 8
    /** Distinct modes that must be WON to unlock [ALL_TWELVE] (every mode). */
    val ALL_TWELVE_TARGET: Int = GameMode.values().size
    /** Distinct modes that must be PLAYED to unlock [FULL_HOUSE] (every mode). */
    val FULL_HOUSE_TARGET: Int = GameMode.values().size
    /** Final total that qualifies a Bermuda game for the treasure-hunter feat. */
    const val BERMUDA_TREASURE_TOTAL: Int = 250

    // ---- Milestones / dedication ----
    private val FIRST_WIN = Achievement(
        id = "first_win",
        title = "First Blood",
        description = "Win your first game.",
        category = "Milestones",
    )
    private val GAMES_10 = Achievement(
        id = "games_10",
        title = "Getting Started",
        description = "Play 10 games.",
        category = "Dedication",
    )
    private val GAMES_50 = Achievement(
        id = "games_50",
        title = "Regular",
        description = "Play 50 games.",
        category = "Dedication",
    )
    private val GAMES_100 = Achievement(
        id = "games_100",
        title = "Centurion",
        description = "Play 100 games.",
        category = "Dedication",
    )
    private val WINS_25 = Achievement(
        id = "wins_25",
        title = "Winner",
        description = "Win 25 games.",
        category = "Dedication",
    )
    private val STREAK_3 = Achievement(
        id = "streak_3",
        title = "On Fire",
        description = "Win 3 games in a row.",
        category = "Milestones",
    )
    private val ALL_ROUNDER = Achievement(
        id = "all_rounder",
        title = "All-Rounder",
        description = "Win a game in 5 different modes.",
        category = "Milestones",
    )

    // ---- X01 ----
    private val TON_CLUB = Achievement(
        id = "ton_club",
        title = "Ton Club",
        description = "Throw a 100+ visit in an X01 game.",
        category = "X01",
    )
    private val ONE_EIGHTY = Achievement(
        id = "one_eighty",
        title = "One Hundred and Eighty!",
        description = "Throw a maximum 180.",
        category = "X01",
    )
    private val HIGH_FINISH_100 = Achievement(
        id = "high_finish_100",
        title = "High Finish",
        description = "Check out 100 or more to finish a leg.",
        category = "X01",
    )
    private val BIG_FISH = Achievement(
        id = "big_fish",
        title = "Big Fish",
        description = "Hit the maximum 170 checkout.",
        category = "X01",
    )
    private val SHARP_18 = Achievement(
        id = "sharp_18",
        title = "Sharp Shooter",
        description = "Win an X01 leg in 18 darts or fewer.",
        category = "X01",
    )

    // ---- Practice modes ----
    private val SHANGHAI_MASTER = Achievement(
        id = "shanghai_master",
        title = "Shanghai!",
        description = "Win instantly with a Shanghai.",
        category = "Practice",
    )
    private val CLOCK_CLEANER = Achievement(
        id = "clock_cleaner",
        title = "Clock Cleaner",
        description = "Win a game of Around the Clock.",
        category = "Practice",
    )

    // ---- New-mode feats (Baseball / Golf / Count-Up / Gotcha / Checkout Trainer) ----
    private val GOLF_UNDER_PAR = Achievement(
        id = "golf_under_par",
        title = "Under Par",
        description = "Finish a Golf game below par (under $GOLF_PAR strokes).",
        category = "Practice",
    )
    private val CHECKOUT_PERFECT = Achievement(
        id = "checkout_perfect",
        title = "Perfect Finisher",
        description = "Hit every target in a Checkout Trainer game.",
        category = "Practice",
    )
    private val BASEBALL_SLUGGER = Achievement(
        id = "baseball_slugger",
        title = "Slugger",
        description = "Score $BASEBALL_SLUGGER_RUNS or more total runs in a Baseball game.",
        category = "Practice",
    )
    private val COUNTUP_HIGH = Achievement(
        id = "countup_high",
        title = "Count-Up Crusher",
        description = "Reach $COUNTUP_HIGH_TOTAL or more in a Count-Up game.",
        category = "Practice",
    )
    private val GOTCHA_WINNER = Achievement(
        id = "gotcha_winner",
        title = "Gotcha!",
        description = "Win a game of Gotcha.",
        category = "Practice",
    )
    private val KILLER_WINNER = Achievement(
        id = "killer_winner",
        title = "Last One Standing",
        description = "Win a game of Killer.",
        category = "Practice",
    )
    private val BERMUDA_WINNER = Achievement(
        id = "bermuda_winner",
        title = "Treasure Island",
        description = "Win a game of Bermuda.",
        category = "Practice",
    )
    private val CRICKET_WINNER = Achievement(
        id = "cricket_winner",
        title = "Marksman",
        description = "Win a game of Cricket.",
        category = "Practice",
    )
    private val HALF_IT_WINNER = Achievement(
        id = "half_it_winner",
        title = "Half Measures",
        description = "Win a game of Half-It.",
        category = "Practice",
    )
    private val KILLER_UNTOUCHABLE = Achievement(
        id = "killer_untouchable",
        title = "Untouchable",
        description = "Win a game of Killer without losing a life.",
        category = "Practice",
    )
    private val BERMUDA_TREASURE = Achievement(
        id = "bermuda_treasure",
        title = "Treasure Hunter",
        description = "Reach a total of $BERMUDA_TREASURE_TOTAL or more in a Bermuda game.",
        category = "Practice",
    )

    // ---- CPU opponent ----
    private val BOT_SLAYER_HARD = Achievement(
        id = "bot_slayer_hard",
        title = "Machine Beater",
        description = "Beat a Hard CPU opponent in X01 or Count-Up.",
        category = "CPU",
    )
    private val BOT_SLAYER_PRO = Achievement(
        id = "bot_slayer_pro",
        title = "Pro Slayer",
        description = "Beat a Pro CPU opponent in X01 or Count-Up.",
        category = "CPU",
    )

    // ---- Breadth / dedication across modes ----
    private val MODE_EXPLORER = Achievement(
        id = "mode_explorer",
        title = "Explorer",
        description = "Play $MODE_EXPLORER_TARGET different modes.",
        category = "Modes",
    )
    private val STREAK_5 = Achievement(
        id = "streak_5",
        title = "Unstoppable",
        description = "Win 5 games in a row.",
        category = "Milestones",
    )
    private val ALL_TWELVE = Achievement(
        id = "all_twelve",
        title = "Jack of All Trades",
        description = "Win a game in all $ALL_TWELVE_TARGET modes.",
        category = "Modes",
    )
    private val FULL_HOUSE = Achievement(
        id = "full_house",
        title = "Full House",
        description = "Play all $FULL_HOUSE_TARGET game modes at least once.",
        category = "Modes",
    )

    val all: List<Achievement> = listOf(
        FIRST_WIN,
        GAMES_10,
        GAMES_50,
        GAMES_100,
        WINS_25,
        TON_CLUB,
        ONE_EIGHTY,
        HIGH_FINISH_100,
        BIG_FISH,
        SHARP_18,
        SHANGHAI_MASTER,
        CLOCK_CLEANER,
        STREAK_3,
        ALL_ROUNDER,
        GOLF_UNDER_PAR,
        CHECKOUT_PERFECT,
        BASEBALL_SLUGGER,
        COUNTUP_HIGH,
        GOTCHA_WINNER,
        KILLER_WINNER,
        BERMUDA_WINNER,
        CRICKET_WINNER,
        HALF_IT_WINNER,
        KILLER_UNTOUCHABLE,
        BERMUDA_TREASURE,
        BOT_SLAYER_HARD,
        BOT_SLAYER_PRO,
        MODE_EXPLORER,
        STREAK_5,
        ALL_TWELVE,
        FULL_HOUSE,
    )
}

/**
 * Internal mutable accumulator for one countable/dated achievement. [count] is
 * the raw progress toward [target]; [firstQualifiedAtMs] tracks the earliest
 * game (by [GameRecord.createdAtEpochMs]) that pushed [count] to >= [target].
 */
private class Tally(val target: Int) {
    var count: Int = 0
    var firstQualifiedAtMs: Long? = null

    /** Record that [atMs]'s game contributed [by] toward this tally. */
    fun add(atMs: Long, by: Int = 1) {
        val before = count
        count += by
        if (firstQualifiedAtMs == null && before < target && count >= target) {
            firstQualifiedAtMs = atMs
        }
    }

    /**
     * Record a single qualifying event from the game at [atMs] (idempotent flag
     * style: any one event unlocks). Keeps the EARLIEST timestamp seen.
     */
    fun mark(atMs: Long) {
        count = count.coerceAtLeast(target)
        firstQualifiedAtMs =
            if (firstQualifiedAtMs == null) atMs else minOf(firstQualifiedAtMs!!, atMs)
    }

    val unlocked: Boolean get() = count >= target

    fun toStatus(achievement: Achievement): AchievementStatus = AchievementStatus(
        achievement = achievement,
        unlocked = unlocked,
        progress = count.coerceAtMost(target),
        target = target,
        unlockedAtMs = if (unlocked) firstQualifiedAtMs else null,
    )
}

/**
 * Compute the [AchievementStatus] of every catalog achievement for [playerId]
 * across [games], in catalog order.
 *
 * Seat resolution mirrors [playerStats]: the player's seat in a game is the
 * first index `i` where `state.players[i].id == playerId`; games with no such
 * seat are skipped, and a blank [playerId] matches nothing (all locked).
 *
 * unlockedAtMs determination: for each achievement we keep the earliest
 * [GameRecord.createdAtEpochMs] of a qualifying game. For tiered "play/win N"
 * milestones that is the Nth qualifying game (after sorting chronologically by
 * createdAtEpochMs so the count crosses the bar in true play order); for
 * one-shot feats (e.g. a 180, a Shanghai) it is the earliest game exhibiting the
 * feat. Achievements whose qualifying game cannot be pinpointed report null even
 * once unlocked — here that is the win-streak ([streak_3] / [streak_5]) and the
 * distinct-mode achievements ([all_rounder] / [mode_explorer] / [all_twelve]),
 * the breadth achievement [full_house]), which all depend on a SET of games
 * rather than one.
 */
fun achievementsFor(playerId: String, games: List<GameRecord>): List<AchievementStatus> {
    val firstWin = Tally(target = 1)
    val games10 = Tally(target = 10)
    val games50 = Tally(target = 50)
    val games100 = Tally(target = 100)
    val wins25 = Tally(target = 25)
    val tonClub = Tally(target = 1)
    val oneEighty = Tally(target = 1)
    val highFinish100 = Tally(target = 1)
    val bigFish = Tally(target = 1)
    val sharp18 = Tally(target = 1)
    val shanghaiMaster = Tally(target = 1)
    val clockCleaner = Tally(target = 1)
    // New-mode one-shot feats (each unlocks on the earliest qualifying game).
    val golfUnderPar = Tally(target = 1)
    val checkoutPerfect = Tally(target = 1)
    val baseballSlugger = Tally(target = 1)
    val countUpHigh = Tally(target = 1)
    val gotchaWinner = Tally(target = 1)
    val killerWinner = Tally(target = 1)
    val bermudaWinner = Tally(target = 1)
    val cricketWinner = Tally(target = 1)
    val halfItWinner = Tally(target = 1)
    val killerUntouchable = Tally(target = 1)
    val bermudaTreasure = Tally(target = 1)
    val botSlayerHard = Tally(target = 1)
    val botSlayerPro = Tally(target = 1)

    // Streak + distinct-mode achievements depend on relationships ACROSS games,
    // so their qualifying game is not a single record -> unlockedAtMs stays null.
    var bestStreak = 0
    val modesWon = HashSet<GameMode>()
    // Distinct modes merely PLAYED (any seat), for the breadth achievement.
    val modesPlayed = HashSet<GameMode>()

    if (playerId.isNotBlank()) {
        // Sort chronologically by createdAtEpochMs: tiered "Nth game" timestamps
        // and the win streak both require true play order (the repository hands
        // lists sorted by updatedAt, so an explicit sort is required). Ties keep
        // their relative order via a stable sort.
        val ordered = games.sortedBy { it.createdAtEpochMs }
        var currentStreak = 0

        for (r in ordered) {
            val state = r.state
            val idx = state.players.indexOfFirst { it.id == playerId }
            if (idx < 0) continue
            val at = r.createdAtEpochMs
            val won = state.winnerIndices.contains(idx)

            // Participation / win milestones.
            games10.add(at)
            games50.add(at)
            games100.add(at)
            modesPlayed.add(r.mode)
            if (won) {
                firstWin.add(at)
                wins25.add(at)
                modesWon.add(r.mode)
            }

            // Win streak (current best run of consecutive wins in play order).
            currentStreak = if (won) currentStreak + 1 else 0
            if (currentStreak > bestStreak) bestStreak = currentStreak

            // X01-specific feats. Safe cast keeps future GameState subtypes safe.
            val x01 = state as? X01State
            if (x01 != null) {
                val legs = x01.allLegStatesFor(idx)
                legs.forEachIndexed { legIndex, ps ->
                    // Per-visit feats: 180s and 100+ "ton" visits (non-bust).
                    for (t in ps.turns) {
                        if (!t.bust) {
                            if (t.entered == 180) oneEighty.mark(at)
                            if (t.entered >= 100) tonClub.mark(at)
                        }
                    }

                    // Finishing-checkout feats use the per-leg snapshot's last
                    // finished turn (X01Stats.checkout returns its entered value).
                    val checkout = X01Stats.checkout(ps)
                    if (checkout != null) {
                        if (checkout >= 100) highFinish100.mark(at)
                        if (checkout == 170) bigFish.mark(at)
                    }

                    // "Sharp" leg: a WON leg the player finished in <= 18 darts.
                    // A completed leg's winner comes from its snapshot; the live
                    // (final) leg's winner comes from winnerIndices (mirrors the
                    // leg-resolution in playerStats).
                    val wonThisLeg = if (legIndex < x01.completedLegs.size) {
                        x01.completedLegs[legIndex].winnerIndex == idx
                    } else {
                        x01.winnerIndices.contains(idx)
                    }
                    if (wonThisLeg && ps.turns.lastOrNull()?.finished == true) {
                        val darts = ps.turns.size * 3
                        if (darts in 1..18) sharp18.mark(at)
                    }
                }
            }

            // Shanghai instant win: a won Shanghai game whose winner's last turn
            // is itself a Shanghai (one single + one double + one triple).
            val shanghai = state as? ShanghaiState
            if (shanghai != null && won) {
                if (shanghai.perPlayer[idx].turns.lastOrNull()?.isShanghai == true) {
                    shanghaiMaster.mark(at)
                }
            }

            // Around the Clock: simply win a game.
            if (state is AroundTheClockState && won) {
                clockCleaner.mark(at)
            }

            // Golf: finish all holes (the player has a result for every hole) and
            // beat par. A safe cast keeps unknown future modes from breaking this.
            val golf = state as? GolfState
            if (golf != null) {
                val me = golf.perPlayer[idx]
                if (me.holesPlayed >= GOLF_HOLES && me.strokes < AchievementCatalog.GOLF_PAR) {
                    golfUnderPar.mark(at)
                }
            }

            // Checkout Trainer: hit every target on the ladder (a flawless run).
            val checkoutTrainer = state as? CheckoutTrainerState
            if (checkoutTrainer != null) {
                val me = checkoutTrainer.perPlayer[idx]
                if (checkoutTrainer.targets.isNotEmpty() &&
                    me.hits == checkoutTrainer.targets.size
                ) {
                    checkoutPerfect.mark(at)
                }
            }

            // Baseball: rack up a big total of runs in a single game.
            val baseball = state as? BaseballState
            if (baseball != null) {
                if (baseball.perPlayer[idx].total >= AchievementCatalog.BASEBALL_SLUGGER_RUNS) {
                    baseballSlugger.mark(at)
                }
            }

            // Count-Up: reach a high cumulative total in a single game.
            val countUp = state as? CountUpState
            if (countUp != null) {
                if (countUp.perPlayer[idx].total >= AchievementCatalog.COUNTUP_HIGH_TOTAL) {
                    countUpHigh.mark(at)
                }
            }

            // Gotcha: simply win a game.
            if (state is GotchaState && won) {
                gotchaWinner.mark(at)
            }

            // Cricket: simply win a game.
            if (state is CricketState && won) {
                cricketWinner.mark(at)
            }

            // Half-It: simply win a game.
            if (state is HalfItState && won) {
                halfItWinner.mark(at)
            }

            // Killer: simply win a game.
            val killer = state as? KillerState
            if (killer != null && won) {
                killerWinner.mark(at)
                // Untouchable: a SOLE-winner Killer game (no multi-winner draw)
                // where this player's lives never dropped below the start count.
                if (killer.winnerIndices.size == 1 &&
                    killer.perPlayer[idx].lives == killer.startLives
                ) {
                    killerUntouchable.mark(at)
                }
            }

            // Bermuda: simply win a game.
            val bermuda = state as? BermudaState
            if (bermuda != null) {
                if (won) bermudaWinner.mark(at)
                // Treasure Hunter: reach a big final total (won or not).
                if (bermuda.perPlayer[idx].total >= AchievementCatalog.BERMUDA_TREASURE_TOTAL) {
                    bermudaTreasure.mark(at)
                }
            }

            // CPU opponent: win (as a human seat) a game in which an OPPONENT seat
            // is a bot of the given level. The bot is only wired into X01 and
            // Count-Up, but this keys on the seat metadata rather than the mode, so
            // it stays correct if a bot is ever seated in another mode. We require
            // the player's own seat to be human so a bot can't "earn" the feat.
            if (won && !state.players[idx].isBot) {
                val beatenLevels = state.players
                    .filterIndexed { i, p -> i != idx && p.isBot }
                    .mapNotNull { it.botLevel }
                if (beatenLevels.contains(BotLevel.PRO)) botSlayerPro.mark(at)
                if (beatenLevels.contains(BotLevel.HARD)) botSlayerHard.mark(at)
            }
        }
    }

    val streak3 = AchievementStatus(
        achievement = AchievementCatalog.all.first { it.id == "streak_3" },
        unlocked = bestStreak >= 3,
        progress = bestStreak.coerceAtMost(3),
        target = 3,
        unlockedAtMs = null, // spans multiple games; no single qualifying record
    )
    val allRounder = AchievementStatus(
        achievement = AchievementCatalog.all.first { it.id == "all_rounder" },
        unlocked = modesWon.size >= 5,
        progress = modesWon.size.coerceAtMost(5),
        target = 5,
        unlockedAtMs = null, // spans multiple games; no single qualifying record
    )
    val streak5 = AchievementStatus(
        achievement = AchievementCatalog.all.first { it.id == "streak_5" },
        unlocked = bestStreak >= 5,
        progress = bestStreak.coerceAtMost(5),
        target = 5,
        unlockedAtMs = null, // spans multiple games; no single qualifying record
    )
    val modeExplorer = AchievementStatus(
        achievement = AchievementCatalog.all.first { it.id == "mode_explorer" },
        unlocked = modesPlayed.size >= AchievementCatalog.MODE_EXPLORER_TARGET,
        progress = modesPlayed.size.coerceAtMost(AchievementCatalog.MODE_EXPLORER_TARGET),
        target = AchievementCatalog.MODE_EXPLORER_TARGET,
        unlockedAtMs = null, // spans multiple games; no single qualifying record
    )
    val allTwelve = AchievementStatus(
        achievement = AchievementCatalog.all.first { it.id == "all_twelve" },
        unlocked = modesWon.size >= AchievementCatalog.ALL_TWELVE_TARGET,
        progress = modesWon.size.coerceAtMost(AchievementCatalog.ALL_TWELVE_TARGET),
        target = AchievementCatalog.ALL_TWELVE_TARGET,
        unlockedAtMs = null, // spans multiple games; no single qualifying record
    )
    val fullHouse = AchievementStatus(
        achievement = AchievementCatalog.all.first { it.id == "full_house" },
        unlocked = modesPlayed.size >= AchievementCatalog.FULL_HOUSE_TARGET,
        progress = modesPlayed.size.coerceAtMost(AchievementCatalog.FULL_HOUSE_TARGET),
        target = AchievementCatalog.FULL_HOUSE_TARGET,
        unlockedAtMs = null, // spans multiple games; no single qualifying record
    )

    val byId: Map<String, AchievementStatus> = buildMap {
        put("first_win", firstWin.toStatus(AchievementCatalog.all.first { it.id == "first_win" }))
        put("games_10", games10.toStatus(AchievementCatalog.all.first { it.id == "games_10" }))
        put("games_50", games50.toStatus(AchievementCatalog.all.first { it.id == "games_50" }))
        put("games_100", games100.toStatus(AchievementCatalog.all.first { it.id == "games_100" }))
        put("wins_25", wins25.toStatus(AchievementCatalog.all.first { it.id == "wins_25" }))
        put("ton_club", tonClub.toStatus(AchievementCatalog.all.first { it.id == "ton_club" }))
        put("one_eighty", oneEighty.toStatus(AchievementCatalog.all.first { it.id == "one_eighty" }))
        put("high_finish_100", highFinish100.toStatus(AchievementCatalog.all.first { it.id == "high_finish_100" }))
        put("big_fish", bigFish.toStatus(AchievementCatalog.all.first { it.id == "big_fish" }))
        put("sharp_18", sharp18.toStatus(AchievementCatalog.all.first { it.id == "sharp_18" }))
        put("shanghai_master", shanghaiMaster.toStatus(AchievementCatalog.all.first { it.id == "shanghai_master" }))
        put("clock_cleaner", clockCleaner.toStatus(AchievementCatalog.all.first { it.id == "clock_cleaner" }))
        put("golf_under_par", golfUnderPar.toStatus(AchievementCatalog.all.first { it.id == "golf_under_par" }))
        put("checkout_perfect", checkoutPerfect.toStatus(AchievementCatalog.all.first { it.id == "checkout_perfect" }))
        put("baseball_slugger", baseballSlugger.toStatus(AchievementCatalog.all.first { it.id == "baseball_slugger" }))
        put("countup_high", countUpHigh.toStatus(AchievementCatalog.all.first { it.id == "countup_high" }))
        put("gotcha_winner", gotchaWinner.toStatus(AchievementCatalog.all.first { it.id == "gotcha_winner" }))
        put("killer_winner", killerWinner.toStatus(AchievementCatalog.all.first { it.id == "killer_winner" }))
        put("bermuda_winner", bermudaWinner.toStatus(AchievementCatalog.all.first { it.id == "bermuda_winner" }))
        put("cricket_winner", cricketWinner.toStatus(AchievementCatalog.all.first { it.id == "cricket_winner" }))
        put("half_it_winner", halfItWinner.toStatus(AchievementCatalog.all.first { it.id == "half_it_winner" }))
        put("killer_untouchable", killerUntouchable.toStatus(AchievementCatalog.all.first { it.id == "killer_untouchable" }))
        put("bermuda_treasure", bermudaTreasure.toStatus(AchievementCatalog.all.first { it.id == "bermuda_treasure" }))
        put("bot_slayer_hard", botSlayerHard.toStatus(AchievementCatalog.all.first { it.id == "bot_slayer_hard" }))
        put("bot_slayer_pro", botSlayerPro.toStatus(AchievementCatalog.all.first { it.id == "bot_slayer_pro" }))
        put("streak_3", streak3)
        put("all_rounder", allRounder)
        put("streak_5", streak5)
        put("mode_explorer", modeExplorer)
        put("all_twelve", allTwelve)
        put("full_house", fullHouse)
    }

    // Emit one status per catalog entry, in catalog order.
    return AchievementCatalog.all.map { ach ->
        byId[ach.id] ?: AchievementStatus(
            achievement = ach,
            unlocked = false,
            progress = 0,
            target = 1,
            unlockedAtMs = null,
        )
    }
}

/**
 * Headline [AchievementSummary] for [playerId]: how many catalog achievements are
 * unlocked out of the total. A blank [playerId] yields 0 unlocked.
 */
fun achievementSummary(playerId: String, games: List<GameRecord>): AchievementSummary {
    val statuses = achievementsFor(playerId, games)
    return AchievementSummary(
        unlocked = statuses.count { it.unlocked },
        total = statuses.size,
    )
}
