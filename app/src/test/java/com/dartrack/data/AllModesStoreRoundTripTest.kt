package com.dartrack.data

import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BaseballState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CountUpState
import com.dartrack.model.CricketState
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.GolfResult
import com.dartrack.model.GolfState
import com.dartrack.model.GotchaState
import com.dartrack.model.HalfItState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADVERSARIAL persistence test: a [GameStore] holding ONE game of EVERY one of
 * the 12 [GameMode]s must encode then decode to an EQUAL list. This is the
 * catch-all guard for the polymorphic [com.dartrack.model.GameState] registry —
 * a missing sealed subtype registration or a duplicate @SerialName would surface
 * here as a decode failure (which [decodeGameStore] silently wipes to empty) or
 * an inequality, rather than only blowing up in production.
 */
class AllModesStoreRoundTripTest {

    private val players = listOf(GamePlayer("Alice", "pid-a"), GamePlayer("Bob", "pid-b"))

    /** One in-progress (or finished) record per mode, with a distinct id. */
    private fun oneOfEveryMode(): List<GameRecord> {
        var t = 0L
        fun rec(id: String, mode: GameMode, state: com.dartrack.model.GameState) =
            GameRecord(id, mode, createdAtEpochMs = ++t, updatedAtEpochMs = ++t, state = state)

        return listOf(
            rec("x01", GameMode.X01,
                X01State.new(players, startScore = 501, doubleOut = true).applyTurn(60)),
            rec("cricket", GameMode.CRICKET,
                CricketState.new(players).applyTurn(mapOf(20 to 3))),
            rec("halfit", GameMode.HALF_IT,
                HalfItState.new(players).applyTurn(15)),
            rec("atc", GameMode.AROUND_CLOCK,
                AroundTheClockState.new(players).applyTurn(2)),
            rec("bobs27", GameMode.BOBS_27,
                BobsTwentySevenState.new(players).applyTurn(1)),
            rec("shanghai", GameMode.SHANGHAI,
                ShanghaiState.new(players).applyTurn(1, 1, 0)),
            rec("catch40", GameMode.CATCH_40,
                Catch40State.new(players).applyTurn(2)),
            rec("countup", GameMode.COUNT_UP,
                CountUpState.new(players).applyTurn(100)),
            rec("checkout", GameMode.CHECKOUT_TRAINER,
                CheckoutTrainerState.new(players).applyAttempt(hit = true, darts = 2)),
            rec("baseball", GameMode.BASEBALL,
                BaseballState.new(players).applyTurn(1, 1, 1)),
            rec("golf", GameMode.GOLF,
                GolfState.new(players).applyResult(GolfResult.TRIPLE)),
            rec("gotcha", GameMode.GOTCHA,
                GotchaState.new(players, target = 501).applyTurn(140)),
        )
    }

    @Test
    fun storeWithEveryMode_encodesAndDecodesToAnEqualList() {
        val records = oneOfEveryMode()
        // Guard the fixture itself stays exhaustive: one record per declared mode.
        assertEquals(
            GameMode.entries.toSet(),
            records.map { it.mode }.toSet(),
            "fixture must include every GameMode exactly once",
        )
        assertEquals(GameMode.entries.size, records.size, "exactly 12 modes")

        val text = encodeGameStore(records)
        val decoded = decodeGameStore(text)

        // The big one: a missing registration / @SerialName collision would make
        // decode throw -> wiped to empty, or yield a non-equal list.
        assertTrue(decoded.isNotEmpty(), "store must not be wiped (would mean a decode failure)")
        assertEquals(records, decoded, "every mode must survive the store round-trip equally")
    }

    @Test
    fun everyModeSerialName_isPresentAndDistinct() {
        // Each state must serialize under a UNIQUE "type" discriminator. Collisions
        // would silently misroute on decode; assert the set of discriminators has
        // size 12 and matches the expected serial names.
        val expected = setOf(
            "x01", "cricket", "halfit", "around_clock", "bobs_27", "shanghai",
            "catch_40", "count_up", "checkout_trainer", "baseball", "golf", "gotcha",
        )
        val seen = mutableSetOf<String>()
        for (r in oneOfEveryMode()) {
            val text = GameJson.format.encodeToString(GameRecord.serializer(), r)
            val m = Regex("\"type\":\"([^\"]+)\"").find(text)
            assertTrue(m != null, "no type discriminator for ${r.mode}: $text")
            val name = m!!.groupValues[1]
            assertTrue(seen.add(name), "duplicate @SerialName '$name' across modes")
        }
        assertEquals(12, seen.size, "expected 12 distinct serial names, got $seen")
        assertEquals(expected, seen, "serial-name set drifted: $seen")
    }

    @Test
    fun decodingTheAllModesStore_throughTheWipeGuard_keepsAllTwelve() {
        // Belt-and-braces: the envelope carries the current schema version, so the
        // wipe guard must let all 12 through (not treat them as legacy).
        val text = encodeGameStore(oneOfEveryMode())
        assertTrue(
            text.contains("\"schemaVersion\":${GameStore.SCHEMA_VERSION}"),
            "current schema marker expected",
        )
        assertEquals(12, decodeGameStore(text).size, "all 12 modes load through the wipe guard")
    }
}
