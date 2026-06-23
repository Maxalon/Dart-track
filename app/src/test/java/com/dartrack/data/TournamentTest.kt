package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.bot.BotLevel
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Worked examples for the round-robin tournament core ([roundRobinSchedule],
 * [createTournament], [standings], [recordResult], [champions], [fillWithCpus]).
 * Schedules and standings are checked against hand-computed invariants so every
 * assertion can be verified on paper. Mirrors the style of [RecordsTest].
 */
class TournamentTest {

    private fun human(name: String, id: String = name.lowercase()) =
        TournamentCompetitor(name = name, playerId = id, isBot = false)

    private fun cpu(name: String, level: BotLevel) =
        TournamentCompetitor(name = name, isBot = true, botLevel = level)

    /** All distinct unordered index pairs for n competitors (the C(n,2) target). */
    private fun allPairs(n: Int): Set<Pair<Int, Int>> {
        val s = HashSet<Pair<Int, Int>>()
        for (i in 0 until n) for (j in i + 1 until n) s.add(i to j)
        return s
    }

    private fun named(vararg names: String) = names.map { human(it) }

    // ---- circle-method schedule invariants -----------------------------------

    @Test
    fun schedule_everyPairExactlyOnce_forN2to6() {
        for (n in 2..6) {
            val rounds = roundRobinSchedule(n)
            val flat = rounds.flatten()
            // Normalise to low-index-first and assert no duplicate pairing.
            val normalised = flat.map { (a, b) -> if (a < b) a to b else b to a }
            assertEquals(normalised.size, normalised.toSet().size, "n=$n: no pair repeats")
            assertEquals(allPairs(n), normalised.toSet(), "n=$n: every distinct pair appears once")
        }
    }

    @Test
    fun schedule_roundCount_evenHasNMinus1_oddHasN() {
        assertEquals(1, roundRobinSchedule(2).size, "n=2 -> 1 round")
        assertEquals(3, roundRobinSchedule(3).size, "n=3 -> 3 rounds (odd, byes)")
        assertEquals(3, roundRobinSchedule(4).size, "n=4 -> 3 rounds")
        assertEquals(5, roundRobinSchedule(5).size, "n=5 -> 5 rounds (odd, byes)")
        assertEquals(5, roundRobinSchedule(6).size, "n=6 -> 5 rounds")
    }

    @Test
    fun schedule_noCompetitorTwiceInARound() {
        for (n in 2..6) {
            roundRobinSchedule(n).forEachIndexed { round, pairs ->
                val seen = HashSet<Int>()
                for ((a, b) in pairs) {
                    assertTrue(seen.add(a), "n=$n round=$round: $a appears twice")
                    assertTrue(seen.add(b), "n=$n round=$round: $b appears twice")
                }
            }
        }
    }

    @Test
    fun schedule_evenN_noByes_everyRoundFull() {
        // Even n: each round pairs all n competitors -> n/2 matches, no idle seat.
        for (n in intArrayOf(2, 4, 6)) {
            roundRobinSchedule(n).forEach { pairs ->
                assertEquals(n / 2, pairs.size, "n=$n: full round of n/2 matches")
            }
        }
    }

    @Test
    fun schedule_oddN_exactlyOneByePerRound() {
        // Odd n: each round has (n-1)/2 matches, so exactly one competitor sits out.
        for (n in intArrayOf(3, 5)) {
            roundRobinSchedule(n).forEachIndexed { round, pairs ->
                assertEquals((n - 1) / 2, pairs.size, "n=$n round=$round: one competitor on a bye")
                val playing = pairs.flatMap { listOf(it.first, it.second) }.toSet()
                assertEquals(n - 1, playing.size, "n=$n round=$round: exactly one idle competitor")
            }
        }
    }

    @Test
    fun schedule_rejectsTooFew() {
        assertFailsWith<IllegalArgumentException> { roundRobinSchedule(1) }
        assertFailsWith<IllegalArgumentException> { roundRobinSchedule(0) }
    }

    // ---- createTournament ----------------------------------------------------

    @Test
    fun createTournament_matchCountIsNChoose2_withStableIds() {
        val comps = named("A", "B", "C", "D") // C(4,2) = 6
        val t = createTournament("t1", "Spring", comps, GameMode.X01)
        assertEquals(6, t.matches.size, "C(4,2) fixtures")
        // Ids are unique and follow the m-<round>-<i>-<j> convention.
        assertEquals(6, t.matches.map { it.id }.toSet().size, "unique match ids")
        t.matches.forEach { m ->
            assertEquals("m-${m.round}-${m.homeIndex}-${m.awayIndex}", m.id, "id encodes round+pair")
            assertTrue(m.homeIndex < m.awayIndex, "home index < away index (canonical)")
            assertFalse(m.played, "fresh matches unplayed")
        }
    }

    @Test
    fun createTournament_rejectsFewerThanTwo() {
        assertFailsWith<IllegalArgumentException> {
            createTournament("t", "X", listOf(human("A")), GameMode.X01)
        }
    }

    // ---- standings: points ---------------------------------------------------

    @Test
    fun standings_pointsFromWinsAndDraws() {
        // 3 competitors, single round-robin (A-B, A-C, B-C). A wins both, B beats C.
        var t = createTournament("t", "P", named("A", "B", "C"), GameMode.X01)
        t = recordResult(t, "m-0-0-1", winnerIndex = 0, gameId = null) // A beats B
        // remaining pairs across rounds: A-C and B-C (order depends on schedule)
        t = recordResultFor(t, 0, 2, winner = 0) // A beats C
        t = recordResultFor(t, 1, 2, winner = 1) // B beats C
        val table = standings(t)
        // A: 2 wins = 6, B: 1 win 1 loss = 3, C: 2 losses = 0.
        assertEquals(listOf(0, 1, 2), table.map { it.competitorIndex }, "A,B,C by points")
        assertEquals(listOf(6, 3, 0), table.map { it.points }, "3 pts per win")
        assertEquals(listOf(2, 1, 0), table.map { it.won }, "win counts")
        assertEquals(listOf(0, 1, 2), table.map { it.lost }, "loss counts")
    }

    @Test
    fun standings_drawCountsForBothCompetitors() {
        var t = createTournament("t", "P", named("A", "B"), GameMode.X01)
        t = recordResultFor(t, 0, 1, winner = null) // draw
        val table = standings(t)
        assertEquals(setOf(0, 1), table.map { it.competitorIndex }.toSet(), "both present")
        table.forEach {
            assertEquals(1, it.drawn, "each drew one")
            assertEquals(1, it.points, "default draw = 1 pt")
            assertEquals(0, it.won, "no wins")
        }
    }

    @Test
    fun standings_onlyPlayedMatchesCount() {
        // Nothing recorded -> everyone 0 played / 0 points.
        val t = createTournament("t", "P", named("A", "B", "C"), GameMode.X01)
        standings(t).forEach {
            assertEquals(0, it.played, "no matches played")
            assertEquals(0, it.points, "no points yet")
        }
    }

    // ---- standings: tiebreaks ------------------------------------------------

    @Test
    fun standings_headToHeadBreaksAPointsTie() {
        // 3 competitors all finish on the SAME points & wins (each 1W-1L), so the
        // mini-table between them decides. Cycle A>B, B>C, C>A makes them equal on
        // head-to-head too — so instead we force a clean 2-way ordering: build a
        // 4-player league where A and B tie on points+wins but A beat B head-to-head.
        var t = createTournament("t", "P", named("A", "B", "C", "D"), GameMode.X01)
        // Arrange: A and B each get the same record, with A beating B directly.
        t = recordResultFor(t, 0, 1, winner = 0) // A beats B  (head-to-head edge to A)
        t = recordResultFor(t, 0, 2, winner = 0) // A beats C
        t = recordResultFor(t, 0, 3, winner = 3) // D beats A
        t = recordResultFor(t, 1, 2, winner = 1) // B beats C
        t = recordResultFor(t, 1, 3, winner = 1) // B beats D
        t = recordResultFor(t, 2, 3, winner = 3) // D beats C
        // A: beat B,C lost to D -> 2W 1L = 6. B: beat C,D lost to A -> 2W 1L = 6.
        // They tie on points (6) and wins (2); A wins the head-to-head, so A above B.
        val table = standings(t)
        val aRow = table.indexOfFirst { it.competitorIndex == 0 }
        val bRow = table.indexOfFirst { it.competitorIndex == 1 }
        assertEquals(6, table[aRow].points, "A on 6")
        assertEquals(6, table[bRow].points, "B on 6")
        assertTrue(aRow < bRow, "A ranks above B on the head-to-head result")
    }

    @Test
    fun standings_nameBreaksAHeadToHeadTie() {
        // Two competitors with identical records who DREW each other: head-to-head
        // is level (both earned the draw point), so the case-insensitive name
        // tiebreak decides. "alice" < "bob".
        var t = createTournament("t", "P", listOf(human("bob"), human("alice")), GameMode.X01)
        t = recordResultFor(t, 0, 1, winner = null) // they drew
        val table = standings(t)
        assertEquals(
            listOf("alice", "bob"),
            table.map { t.competitors[it.competitorIndex].name },
            "equal on points+wins+H2H+losses -> name tiebreak",
        )
    }

    // ---- recordResult --------------------------------------------------------

    @Test
    fun recordResult_marksWinnerAndGameId() {
        var t = createTournament("t", "P", named("A", "B"), GameMode.X01)
        t = recordResult(t, "m-0-0-1", winnerIndex = 1, gameId = "game-xyz")
        val m = t.matches.single()
        assertTrue(m.played, "now played")
        assertEquals(1, m.winnerIndex, "B (index 1) won")
        assertEquals("game-xyz", m.gameId, "game id attached")
    }

    @Test
    fun recordResult_drawIsNullWinner() {
        var t = createTournament("t", "P", named("A", "B"), GameMode.X01)
        t = recordResult(t, "m-0-0-1", winnerIndex = null, gameId = null)
        val m = t.matches.single()
        assertTrue(m.played, "draw still marks played")
        assertNull(m.winnerIndex, "null winner == draw")
    }

    @Test
    fun recordResult_rejectsBogusWinner() {
        val t = createTournament("t", "P", named("A", "B"), GameMode.X01)
        // Index 5 is not one of this match's two competitors (0 and 1).
        assertFailsWith<IllegalArgumentException> {
            recordResult(t, "m-0-0-1", winnerIndex = 5, gameId = null)
        }
    }

    @Test
    fun recordResult_unknownMatchIsNoOp_andIsIdempotent() {
        var t = createTournament("t", "P", named("A", "B"), GameMode.X01)
        val before = t
        t = recordResult(t, "does-not-exist", winnerIndex = 0, gameId = null)
        assertEquals(before, t, "unknown match -> unchanged state")
        // Recording the same result twice yields the same match (idempotent-safe).
        val once = recordResult(t, "m-0-0-1", winnerIndex = 0, gameId = "g")
        val twice = recordResult(once, "m-0-0-1", winnerIndex = 0, gameId = "g")
        assertEquals(once, twice, "re-recording the same outcome is a no-op")
    }

    // ---- nextUnplayedMatch ordering ------------------------------------------

    @Test
    fun nextUnplayedMatch_followsFixtureOrder() {
        var t = createTournament("t", "P", named("A", "B", "C", "D"), GameMode.X01)
        val first = nextUnplayedMatch(t)
        assertNotNull(first, "a fresh tournament has matches")
        assertEquals(t.matches.first().id, first!!.id, "first by round then schedule order")
        // After playing the first fixture, the next pending one is returned.
        t = recordResult(t, first.id, winnerIndex = first.homeIndex, gameId = null)
        val second = nextUnplayedMatch(t)
        assertEquals(t.matches[1].id, second!!.id, "advances to the next fixture")
        // Earlier round is always served before a later round.
        assertTrue(second.round >= first.round, "rounds are non-decreasing")
    }

    @Test
    fun nextUnplayedMatch_nullWhenComplete() {
        val t = playOutAllHomeWins(createTournament("t", "P", named("A", "B", "C"), GameMode.X01))
        assertNull(nextUnplayedMatch(t), "no pending match once complete")
    }

    // ---- isComplete + champions ----------------------------------------------

    @Test
    fun isComplete_falseUntilAllPlayed_thenTrue() {
        var t = createTournament("t", "P", named("A", "B", "C"), GameMode.X01)
        assertFalse(isComplete(t), "fresh tournament not complete")
        assertTrue(champions(t).isEmpty(), "no champion mid-tournament")
        t = playOutAllHomeWins(t)
        assertTrue(isComplete(t), "complete after every fixture played")
    }

    @Test
    fun champions_singleOutrightWinner() {
        // A beats everyone; B beats C; C beats nobody. A is sole champion.
        var t = createTournament("t", "P", named("A", "B", "C"), GameMode.X01)
        t = recordResultFor(t, 0, 1, winner = 0)
        t = recordResultFor(t, 0, 2, winner = 0)
        t = recordResultFor(t, 1, 2, winner = 1)
        assertEquals(listOf(0), champions(t), "A is the lone champion")
    }

    @Test
    fun champions_coChampionsOnIdenticalRecords() {
        // A and B each beat C and draw each other -> identical 1W-1D records, both
        // champions (name is only a presentation tiebreak, not a title decider).
        var t = createTournament("t", "P", named("A", "B", "C"), GameMode.X01)
        t = recordResultFor(t, 0, 1, winner = null) // A draws B
        t = recordResultFor(t, 0, 2, winner = 0)    // A beats C
        t = recordResultFor(t, 1, 2, winner = 1)    // B beats C
        assertEquals(setOf(0, 1), champions(t).toSet(), "A and B are co-champions")
        assertEquals(2, champions(t).size, "exactly two champions")
    }

    // ---- fillWithCpus --------------------------------------------------------

    @Test
    fun fillWithCpus_padsAndCyclesLevels() {
        val humans = listOf(human("Real"))
        val levels = listOf(BotLevel.HARD, BotLevel.EASY)
        val filled = fillWithCpus(humans, targetSize = 4, levels = levels)
        assertEquals(4, filled.size, "padded up to target size")
        assertEquals("Real", filled[0].name, "human kept first")
        // CPUs numbered from 1, cycling HARD, EASY, HARD ...
        assertEquals("CPU 1 (Hard)", filled[1].name, "first CPU named with level label")
        assertEquals("CPU 2 (Easy)", filled[2].name, "second CPU cycles to next level")
        assertEquals("CPU 3 (Hard)", filled[3].name, "third CPU wraps back to first level")
        assertTrue(filled.drop(1).all { it.isBot && it.playerId == null }, "added entrants are CPUs")
        assertEquals(
            listOf(BotLevel.HARD, BotLevel.EASY, BotLevel.HARD),
            filled.drop(1).map { it.botLevel },
            "levels cycle in order",
        )
    }

    @Test
    fun fillWithCpus_leavesAlreadyFullListAlone() {
        val humans = named("A", "B", "C", "D")
        val filled = fillWithCpus(humans, targetSize = 3, levels = listOf(BotLevel.PRO))
        assertEquals(humans, filled, "already >= target -> unchanged")
        // Exactly-at-target is also unchanged.
        assertEquals(named("A", "B"), fillWithCpus(named("A", "B"), 2, listOf(BotLevel.PRO)))
    }

    // ---- toGamePlayer --------------------------------------------------------

    @Test
    fun toGamePlayer_humanKeepsRegistryId() {
        val seat = human("Alice", id = "alice-uuid").toGamePlayer(index = 0)
        assertEquals("Alice", seat.name, "name preserved")
        assertEquals("alice-uuid", seat.id, "registry id used as seat id")
        assertFalse(seat.isBot, "human seat not a bot")
        assertNull(seat.botLevel, "human has no bot level")
    }

    @Test
    fun toGamePlayer_cpuGetsStableBotSeat() {
        val seat = cpu("CPU 1 (Pro)", BotLevel.PRO).toGamePlayer(index = 3)
        assertEquals("CPU 1 (Pro)", seat.name, "name preserved")
        assertEquals("bot:3", seat.id, "stable index-derived bot id (no UUID)")
        assertTrue(seat.isBot, "CPU seat flagged as bot")
        assertEquals(BotLevel.PRO, seat.botLevel, "bot level carried onto the seat")
    }

    // ---- JSON round-trip -----------------------------------------------------

    @Test
    fun jsonRoundTrip_preservesStateWithResultAndCpu() {
        val comps = listOf(human("Alice", "alice"), cpu("CPU 1 (Hard)", BotLevel.HARD))
        var t = createTournament(
            "t-json", "Persisted", comps, GameMode.CRICKET,
            pointsForWin = 2, pointsForDraw = 1, createdAtEpochMs = 42L,
        )
        t = recordResult(t, "m-0-0-1", winnerIndex = 0, gameId = "g-1")

        val text = encodeTournamentStore(listOf(t))
        val decoded = decodeTournamentStore(text)
        assertEquals(1, decoded.size, "one tournament survives the round-trip")
        assertEquals(t, decoded.single(), "state is byte-for-byte equal after decode")

        // Spot-check the CPU competitor and the recorded result specifically.
        val back = decoded.single()
        assertEquals(GameMode.CRICKET, back.mode, "mode preserved")
        assertEquals(2, back.pointsForWin, "custom points preserved")
        assertEquals(42L, back.createdAtEpochMs, "created timestamp preserved")
        val cpuBack = back.competitors[1]
        assertTrue(cpuBack.isBot, "CPU flag preserved")
        assertEquals(BotLevel.HARD, cpuBack.botLevel, "bot level preserved")
        assertNull(cpuBack.playerId, "CPU has no registry id")
        val playedMatch = back.matches.single { it.played }
        assertEquals(0, playedMatch.winnerIndex, "recorded winner preserved")
        assertEquals("g-1", playedMatch.gameId, "recorded game id preserved")
    }

    @Test
    fun decodeTournamentStore_blankOrCorruptIsEmpty() {
        assertTrue(decodeTournamentStore("").isEmpty(), "blank -> empty")
        assertTrue(decodeTournamentStore("not json {{{").isEmpty(), "corrupt -> empty (no throw)")
        // A store stamped with an older schema version is wiped on load.
        val stale = GameJson.format.encodeToString(
            TournamentStore.serializer(),
            TournamentStore(schemaVersion = TournamentStore.SCHEMA_VERSION - 1),
        )
        assertTrue(decodeTournamentStore(stale).isEmpty(), "older schema -> wiped")
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * Record a result by the COMPETITOR pair (i, j) regardless of which round the
     * schedule placed it in, looking up the actual match by its two indices. Lets
     * a test talk in pairings without hard-coding the round number.
     */
    private fun recordResultFor(
        state: TournamentState,
        i: Int,
        j: Int,
        winner: Int?,
    ): TournamentState {
        val lo = minOf(i, j); val hi = maxOf(i, j)
        val match = state.matches.first { it.homeIndex == lo && it.awayIndex == hi }
        return recordResult(state, match.id, winnerIndex = winner, gameId = null)
    }

    /** Play every fixture out with the home competitor winning (for completeness tests). */
    private fun playOutAllHomeWins(state: TournamentState): TournamentState {
        var t = state
        for (m in state.matches) {
            t = recordResult(t, m.id, winnerIndex = m.homeIndex, gameId = null)
        }
        return t
    }
}
