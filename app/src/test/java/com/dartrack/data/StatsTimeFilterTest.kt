package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.X01PlayerState
import com.dartrack.model.X01State
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Calendar-boundary semantics for [filterByRange]. All cases use a FIXED
 * [nowMs] and a FIXED zone (UTC) so the inclusive current-period rules are
 * deterministic. We probe games just inside and just outside each period.
 */
class StatsTimeFilterTest {

    private val UTC: ZoneId = ZoneId.of("UTC")

    // "Now" = 2026-06-20T12:00:00Z (matches the development reference date).
    private val nowMs =
        ZonedDateTime.of(2026, 6, 20, 12, 0, 0, 0, UTC).toInstant().toEpochMilli()

    private fun ms(
        year: Int, month: Int, day: Int,
        hour: Int = 12, minute: Int = 0,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, UTC)
        .toInstant().toEpochMilli()

    private fun record(id: String, createdAtMs: Long): GameRecord {
        val player = GamePlayer(name = "P", id = "p-$id")
        val state = X01State(
            players = listOf(player),
            perPlayer = listOf(X01PlayerState(player, emptyList())),
            startScore = 501,
            winnerIndices = emptyList(),
        )
        return GameRecord(
            id = id,
            mode = GameMode.X01,
            createdAtEpochMs = createdAtMs,
            updatedAtEpochMs = createdAtMs,
            state = state,
        )
    }

    private fun ids(games: List<GameRecord>): Set<String> = games.map { it.id }.toSet()

    // Reference games spanning multiple boundaries.
    private val todayEarly = record("todayEarly", ms(2026, 6, 20, hour = 0, minute = 1))
    private val todayLate = record("todayLate", ms(2026, 6, 20, hour = 23, minute = 59))
    private val yesterday = record("yesterday", ms(2026, 6, 19))
    private val firstOfMonth = record("firstOfMonth", ms(2026, 6, 1, hour = 0, minute = 0))
    private val lastMonth = record("lastMonth", ms(2026, 5, 31, hour = 23, minute = 59))
    private val janThisYear = record("janThisYear", ms(2026, 1, 1, hour = 0, minute = 0))
    private val lastYear = record("lastYear", ms(2025, 12, 31, hour = 23, minute = 59))
    private val twoYearsAgo = record("twoYearsAgo", ms(2024, 6, 20))

    private val all = listOf(
        todayEarly, todayLate, yesterday, firstOfMonth,
        lastMonth, janThisYear, lastYear, twoYearsAgo,
    )

    @Test
    fun day_keepsOnlyToday_excludesYesterday() {
        val out = ids(filterByRange(all, StatsRange.DAY, nowMs, UTC))
        assertEquals(setOf("todayEarly", "todayLate"), out, "DAY = current calendar day, inclusive of its edges")
    }

    @Test
    fun month_includesAllOfThisMonth_excludesLastMonth() {
        val out = ids(filterByRange(all, StatsRange.MONTH, nowMs, UTC))
        assertEquals(
            setOf("todayEarly", "todayLate", "yesterday", "firstOfMonth"),
            out,
            "MONTH = current calendar month; yesterday (same month) in, last month out",
        )
    }

    @Test
    fun year_includesAllOfThisYear_excludesLastYear() {
        val out = ids(filterByRange(all, StatsRange.YEAR, nowMs, UTC))
        assertEquals(
            setOf("todayEarly", "todayLate", "yesterday", "firstOfMonth", "lastMonth", "janThisYear"),
            out,
            "YEAR = current calendar year; last month in, last year out",
        )
    }

    @Test
    fun all_returnsEverything() {
        val out = ids(filterByRange(all, StatsRange.ALL, nowMs, UTC))
        assertEquals(all.map { it.id }.toSet(), out, "ALL keeps every game regardless of date")
    }

    @Test
    fun emptyInput_returnsEmpty_forEveryRange() {
        for (r in StatsRange.entries) {
            assertEquals(emptyList(), filterByRange(emptyList(), r, nowMs, UTC), "empty input must not crash for $r")
        }
    }

    @Test
    fun zoneAware_boundaryShiftsWithZone() {
        // A game at 2026-06-20T01:00Z is "today" in UTC but "yesterday" (19th,
        // 23:00) in UTC-2 -> excluded from DAY there.
        val earlyUtc = record("earlyUtc", ms(2026, 6, 20, hour = 1, minute = 0))
        val games = listOf(earlyUtc)
        assertEquals(
            setOf("earlyUtc"),
            ids(filterByRange(games, StatsRange.DAY, nowMs, UTC)),
            "01:00Z is today in UTC",
        )
        val minus2 = ZoneId.of("Etc/GMT+2") // UTC-2
        val nowMinus2 =
            ZonedDateTime.of(2026, 6, 20, 12, 0, 0, 0, minus2).toInstant().toEpochMilli()
        assertEquals(
            emptySet(),
            ids(filterByRange(games, StatsRange.DAY, nowMinus2, minus2)),
            "01:00Z falls on the 19th in UTC-2, so it is excluded from DAY",
        )
    }
}
