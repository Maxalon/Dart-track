package com.dartrack.model.bot

import com.dartrack.model.CRICKET_MARKS_TO_CLOSE
import com.dartrack.model.CRICKET_TARGETS
import com.dartrack.model.Checkout
import com.dartrack.model.CricketState
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Difficulty ladder for the CPU opponent, anchored to the standard real-world
 * reference points for a player's 3-dart average:
 *
 *  - EASY   (~40): a casual pub thrower (typical pub average is 40-50).
 *  - MEDIUM (~60): a decent club player (60-70 is solid club level).
 *  - HARD   (~80): a strong club / county player (80-90).
 *  - PRO   (~100): a touring professional (a 100+ average wins tournaments).
 *
 * [threeDartAverage] is the *target* mean points scored per three darts. The
 * simulator is calibrated so that, over many [DartsBot] scoring visits, the
 * empirical mean lands on this number (see [DartsBot.expectedThreeDartAverage]).
 */
@Serializable
enum class BotLevel(val label: String, val threeDartAverage: Int) {
    EASY("Easy", 40),
    MEDIUM("Medium", 60),
    HARD("Hard", 80),
    PRO("Pro", 100),
}

/**
 * Pure, Android-free, **seedable & deterministic** statistical model of a CPU
 * darts opponent. Nothing here touches the clock, the filesystem or any global
 * RNG: every random draw goes through the injected [rng], so two bots created
 * with the same seed emit byte-identical sequences of visits. That makes the
 * whole thing trivially unit-testable and reproducible in a game replay.
 *
 * ## How a visit is modelled
 *
 * Real darts is scored three-darts-at-a-time, and the engine ([com.dartrack.model.X01]
 * and Count-Up) only ever consumes a single 0..180 total per turn — so that is
 * exactly what this class produces. A visit is built **dart by dart** from real
 * dartboard segment values and then summed, which is the key trick that
 * guarantees the result is always a *possible* 3-dart total (see below).
 *
 * Each scoring dart is aimed at the treble 20 and resolves to one of a handful
 * of physically-plausible outcomes, weighted by the bot's skill:
 *
 *  - **T20 (60)** with probability [trebleHitProb] — the dart the bot meant to hit.
 *  - **S20 (20)** — just missed the treble but stayed in the fat 20 bed.
 *  - a **neighbour single** (1 or 5, the wedges either side of 20) — a wider miss.
 *  - a **low miss** (a small single, occasionally a complete miss = 0) — a poor dart.
 *
 * The single free parameter [trebleHitProb] is the bot's treble-20 strike rate;
 * everything else is derived from it. It was solved per level so the resulting
 * 3-dart mean equals [BotLevel.threeDartAverage] (EASY ~4.7% trebles up to PRO
 * ~46%). The emergent spread is believable: PRO throws a 180 roughly once every
 * ten visits and is rarely under 26, while EASY is frequently 26-or-less and
 * almost never tons — matching the reference averages.
 *
 * ## Why every total is a legal, possible 3-dart score
 *
 * Because a visit is the **sum of three real single-dart values**, it is by
 * construction a score three darts can actually make, so it can never be one of
 * the mathematically-impossible 3-dart totals (163, 166, 169, 172, 173, 175,
 * 176, 178, 179) nor any of the classic "bogey" numbers. The X01 engine accepts
 * any 0..180 total, and busts are perfectly legal turns there — so a scoring
 * visit is always safe to feed straight into [com.dartrack.model.X01State.applyTurn].
 *
 * ## Checkouts
 *
 * When [x01Visit] is asked to finish a finishable [remaining], the bot makes a
 * checkout attempt whose success probability scales with skill (and shrinks for
 * longer finishes). "Finishable" is delegated to [Checkout.suggest] so the bot
 * stays perfectly consistent with the rest of the app's rules. On success it
 * returns exactly [remaining] — a legal finish. On a miss it returns a realistic
 * non-finishing total that sets up a smaller number (and, rarely, busts).
 */
class DartsBot(val level: BotLevel, private val rng: Random) {

    /** Convenience seeded constructor for deterministic tests / replays. */
    constructor(level: BotLevel, seed: Long) : this(level, Random(seed))

    // ---------------------------------------------------------------- model params

    /**
     * Treble-20 strike rate for this level — the model's single calibration knob.
     * Solved by simulation so the 3-dart mean equals [BotLevel.threeDartAverage].
     */
    private val trebleHitProb: Double = when (level) {
        BotLevel.EASY -> 0.047
        BotLevel.MEDIUM -> 0.182
        BotLevel.HARD -> 0.320
        BotLevel.PRO -> 0.456
    }

    /**
     * Per-dart double strike rate, used only for checkout success. Monotonic in
     * skill: EASY ~0.13 up to PRO ~0.40, in line with real double-hit rates.
     */
    private val doubleHitProb: Double = 0.10 + 0.65 * trebleHitProb

    // ----------------------------------------------------------------- single dart

    /** The wedges immediately either side of the 20 (a near miss off the treble). */
    private val neighbourSingles = intArrayOf(1, 5)

    /**
     * A wider miss: small singles around the top of the board plus the odd
     * complete miss (0). All are real single-dart values, keeping totals legal.
     */
    private val lowMissSingles = intArrayOf(0, 1, 3, 4, 5, 7, 9, 12)

    /**
     * One scoring dart aimed at T20. Returns a real single-dart value, so any
     * sum of three of these is guaranteed to be a possible 3-dart total.
     */
    private fun scoringDart(): Int {
        val rem = 1.0 - trebleHitProb
        // Mass split of the non-treble outcomes (tuned for a believable spread).
        val pStay = rem * 0.45   // stayed in the 20 single bed
        val pNbr = rem * 0.30    // neighbour single (1 or 5)
        val pLow = rem * 0.20    // wider low miss
        // remaining rem * 0.05 -> a clean miss (0), the fall-through below

        var r = rng.nextDouble()
        if (r < trebleHitProb) return 60
        r -= trebleHitProb
        if (r < pStay) return 20
        r -= pStay
        if (r < pNbr) return neighbourSingles[rng.nextInt(neighbourSingles.size)]
        r -= pNbr
        if (r < pLow) return lowMissSingles[rng.nextInt(lowMissSingles.size)]
        return 0
    }

    /**
     * A full three-dart **scoring** visit: the sum of three [scoringDart]s,
     * always in 0..180 and always a possible 3-dart total. This is the shared
     * core reused by both Count-Up and the non-finishing branch of X01.
     */
    private fun scoringVisit(): Int = scoringDart() + scoringDart() + scoringDart()

    // ----------------------------------------------------------------- public API

    /**
     * A realistic Count-Up visit: a plain 0..180 scoring visit centred on the
     * level's average. Count-Up has no targets or busts, so this is just the
     * scoring core.
     */
    fun countUpVisit(): Int = scoringVisit()

    /**
     * The bot's three-dart total for an X01 turn with [remaining] points left.
     *
     * Guarantees the result is in 0..180 and is a possible 3-dart total that the
     * X01 engine will accept:
     *  - If [remaining] is **not** finishable in three darts, returns an ordinary
     *    scoring visit (which the engine applies, or treats as a legal bust).
     *  - If [remaining] **is** finishable, the bot attempts the checkout. On
     *    success it returns exactly [remaining] (a legal finish). On a miss it
     *    returns a non-finishing total: usually a partial score that sets up a
     *    smaller number, and occasionally a bust — but never an illegal total.
     */
    fun x01Visit(remaining: Int, doubleOut: Boolean): Int {
        val routes = Checkout.suggest(remaining, doubleOut)
        if (routes.isEmpty()) {
            // Not on a finish: just score. A scoring visit is always a legal
            // 0..180 total; if it overshoots [remaining] the engine busts it,
            // which is a perfectly valid X01 turn.
            return scoringVisit()
        }

        // On a finish. Success probability scales with skill and shrinks for the
        // longer finishes (more darts that all have to land). We never return a
        // forbidden single-visit total even on a "finish": the handful of legal
        // 159..169 finishes are vanishingly rare in real play, so the bot simply
        // scores instead of clicking them, keeping every output a sanctioned total.
        val dartsNeeded = (routes.first().count { it == ' ' } + 1).coerceIn(1, 3)
        val finishProb = checkoutProbability(dartsNeeded)
        if (remaining !in FORBIDDEN_VISIT_TOTALS && rng.nextDouble() < finishProb) return remaining

        // Missed the checkout: return a believable non-finishing total.
        return missedCheckoutVisit(remaining, doubleOut)
    }

    /**
     * Probability of completing a [dartsNeeded]-dart checkout within the visit.
     * Built from [doubleHitProb] with up to [dartsNeeded] genuine attempts at the
     * finishing double; longer routes leave fewer attempts, so are harder. Always
     * strictly inside (0, 1), and strictly increasing in skill.
     */
    private fun checkoutProbability(dartsNeeded: Int): Double {
        val attempts = (4 - dartsNeeded).coerceIn(1, 3) // 1-dart finish -> 3 tries, 3-dart -> 1
        val pAtLeastOne = 1.0 - Math.pow(1.0 - doubleHitProb, attempts.toDouble())
        return pAtLeastOne.coerceIn(0.01, 0.99)
    }

    /**
     * A non-finishing total for a *missed* checkout. We never want to accidentally
     * return [remaining] (that would be a finish), so we draw a scoring visit and,
     * if it lands exactly on [remaining] or on an illegal-finish value, fold it
     * down to a safe non-finishing total. Busts (overshooting, or leaving 1 under
     * double-out) are allowed and modelled, just rarely.
     */
    private fun missedCheckoutVisit(remaining: Int, doubleOut: Boolean): Int {
        repeat(8) {
            val v = scoringVisit()
            if (v != remaining) {
                // For small remainders a scoring visit usually overshoots; that is
                // a legal bust. We only need to avoid the exact-finish value.
                return v
            }
        }
        // Extremely unlikely fall-through: leave the smallest safe non-finish.
        return if (remaining >= 2) 0 else 0
    }

    // ---------------------------------------------------------------- calibration

    /**
     * The analytic expected three-dart score for this level, derived directly
     * from the model parameters (no sampling). Used by tests to assert the
     * calibration: it should sit on [BotLevel.threeDartAverage].
     *
     * E[dart] = 60*pT + 20*pStay + mean(neighbours)*pNbr + mean(lowMiss)*pLow,
     * and a visit is three independent darts, so E[visit] = 3 * E[dart].
     */
    fun expectedThreeDartAverage(): Double {
        val rem = 1.0 - trebleHitProb
        val pStay = rem * 0.45
        val pNbr = rem * 0.30
        val pLow = rem * 0.20
        val meanNbr = neighbourSingles.average()
        val meanLow = lowMissSingles.average()
        val perDart = 60.0 * trebleHitProb + 20.0 * pStay + meanNbr * pNbr + meanLow * pLow
        return 3.0 * perDart
    }

    // -------------------------------------------------------------------- cricket

    /**
     * Per-dart **mark strike rate** for Cricket — the model's single calibration
     * knob for this game, mirroring how [trebleHitProb] is the one knob for the
     * scoring core. It is the probability that a dart aimed at the bot's chosen
     * Cricket target actually lands somewhere in that number's wedge (a single,
     * double or triple of it). The complement is a complete miss (0 marks).
     *
     * Chosen so the expected marks per 3-dart visit climbs cleanly with skill —
     * see [expectedCricketMarksPerVisit] for the closed-form values:
     *
     *  - EASY   0.34 → ~1.7 marks/visit (a casual thrower barely closes a number a turn)
     *  - MEDIUM 0.60 → ~3.1 marks/visit (a number closed most turns)
     *  - HARD   0.80 → ~4.1 marks/visit
     *  - PRO    0.98 → ~5.0 marks/visit (a strong turn that often closes a number with marks to spare)
     *
     * These are deliberately *below* the theoretical ceiling of 9: even a pro
     * sprays the occasional dart, and aiming switches mid-visit as numbers close.
     */
    private val cricketMarkHitProb: Double = when (level) {
        BotLevel.EASY -> 0.34
        BotLevel.MEDIUM -> 0.60
        BotLevel.HARD -> 0.80
        BotLevel.PRO -> 0.98
    }

    /**
     * When a Cricket dart DOES land in the aimed number, how the hit splits across
     * single / double / triple of that number. Calibrated so that **single is the
     * most common hit and triple the rarest** (a believable real-board spread:
     * the fat single bed is far easier to catch than the thin treble ring), while
     * still giving the higher levels enough trebles to be productive. These
     * weights are skill-independent; only [cricketMarkHitProb] scales with level,
     * keeping the model a single-knob calibration like the scoring core.
     *
     * Mean marks PER LANDED dart = 1·0.50 + 2·0.30 + 3·0.20 = 1.7.
     */
    private val cricketSingleWeight = 0.50
    private val cricketDoubleWeight = 0.30
    private val cricketTripleWeight = 0.20

    /**
     * Resolve ONE Cricket dart aimed at a target into the number of marks it
     * adds (0..3). With probability [cricketMarkHitProb] the dart catches the
     * number and yields single (1) / double (2) / triple (3) marks per the
     * [cricketSingleWeight]/[cricketDoubleWeight]/[cricketTripleWeight] split;
     * otherwise it misses for 0 marks. All randomness flows through [rng].
     */
    private fun cricketDartMarks(): Int {
        if (rng.nextDouble() >= cricketMarkHitProb) return 0 // missed the number entirely
        val r = rng.nextDouble()
        return when {
            r < cricketSingleWeight -> 1
            r < cricketSingleWeight + cricketDoubleWeight -> 2
            else -> 3
        }
    }

    /**
     * Pick the target this dart should aim at, given the bot's OWN running
     * cumulative marks this visit ([myMarks]) and the full [state] (to know which
     * numbers opponents have already closed, so extra marks would actually score).
     *
     * Priority — a believable, non-optimal heuristic:
     *  1. **Close your numbers, highest value first.** The first target in
     *     [CRICKET_TARGETS] order (20,19,18,17,16,15,25) the bot has not yet
     *     closed (running marks < 3). This makes the bot grind 20 → 19 → … down.
     *  2. **Score when fully closed.** If the bot has closed everything, aim at
     *     the highest-value target it has closed but at least one opponent has
     *     NOT, so the extra marks bank points.
     *  3. **Harmless fallback.** If the whole board is closed by everyone (no way
     *     to score), aim at 20 — a wasted dart that changes nothing.
     */
    private fun cricketAimTarget(state: CricketState, playerIndex: Int, myMarks: Map<Int, Int>): Int {
        // 1. Highest-value still-open number for the bot.
        CRICKET_TARGETS.firstOrNull { (myMarks[it] ?: 0) < CRICKET_MARKS_TO_CLOSE }?.let { return it }

        // 2. Everything closed: aim where extra marks still score points.
        CRICKET_TARGETS.firstOrNull { target ->
            val opponentStillOpen = state.perPlayer.indices.any { idx ->
                idx != playerIndex && !state.perPlayer[idx].isClosed(target)
            }
            opponentStillOpen
        }?.let { return it }

        // 3. Board fully closed everywhere: a harmless dart.
        return 20
    }

    /**
     * The marks this [DartsBot] adds on one 3-dart Cricket visit, keyed by target
     * (only positive entries, keys ⊆ [CRICKET_TARGETS], total in 0..9 — exactly
     * what [CricketState.applyTurn] accepts).
     *
     * Built **dart by dart**: the aimed target is re-chosen for every dart from
     * the bot's marks *updated as this visit lands*, via [cricketAimTarget]. That
     * means once a dart closes a number the next dart immediately moves on instead
     * of wasting marks on it — the same "don't throw at a closed number" instinct
     * a real player has. Each dart's marks come from [cricketDartMarks]. Pure and
     * deterministic: identical [state]/[playerIndex] under an equally-seeded [rng]
     * yields an identical visit.
     */
    fun cricketVisit(state: CricketState, playerIndex: Int): Map<Int, Int> {
        // Local running tally of the bot's own marks, seeded from its history and
        // advanced as each of this visit's darts lands.
        val myMarks = state.perPlayer[playerIndex].cumulativeMarks().toMutableMap()
        val added = HashMap<Int, Int>()
        repeat(3) {
            val target = cricketAimTarget(state, playerIndex, myMarks)
            val marks = cricketDartMarks()
            if (marks > 0) {
                myMarks[target] = (myMarks[target] ?: 0) + marks
                added[target] = (added[target] ?: 0) + marks
            }
        }
        return added
    }

    /**
     * Closed-form expected marks per 3-dart Cricket visit for this level (no
     * sampling), used by tests to lock the calibration and its monotonicity.
     * Each dart independently contributes [cricketMarkHitProb] × (mean marks per
     * landed dart = 1.7), and a visit is three such darts.
     */
    fun expectedCricketMarksPerVisit(): Double {
        val meanPerLanded =
            1.0 * cricketSingleWeight + 2.0 * cricketDoubleWeight + 3.0 * cricketTripleWeight
        return 3.0 * cricketMarkHitProb * meanPerLanded
    }

    companion object {
        /**
         * Three-dart totals the bot will never emit. The first group are
         * mathematically impossible as a sum of three real darts; the rest are the
         * classic "bogey" numbers a player can't (or realistically won't) make in a
         * single visit. The scoring core already can't produce any of these, and we
         * additionally refuse to "finish" on one (see [x01Visit]) so every value the
         * bot returns is a sanctioned 3-dart total.
         */
        val FORBIDDEN_VISIT_TOTALS: Set<Int> = setOf(
            163, 166, 169, 172, 173, 175, 176, 178, 179, // impossible sums
            159, 162, 165, 168, 171,                     // bogey numbers
        )
    }
}
