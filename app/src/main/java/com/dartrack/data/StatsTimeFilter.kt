package com.dartrack.data

import java.time.Instant
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId

/**
 * Time-range buckets for the statistics screens. Each bucket (other than [ALL])
 * is an INCLUSIVE current-calendar-period filter, NOT a rolling window:
 *  - [DAY]   = the current calendar day in the active zone.
 *  - [MONTH] = the current calendar month.
 *  - [YEAR]  = the current calendar year.
 *  - [ALL]   = every game, no filtering.
 */
enum class StatsRange { DAY, MONTH, YEAR, ALL }

/**
 * Filter [games] down to those whose [GameRecord.createdAtEpochMs] falls inside
 * the calendar period implied by [range], relative to [nowMs] interpreted in
 * [zone].
 *
 * Semantics are calendar-boundary based (zone-aware), not a rolling 24h/30d/365d
 * window:
 *  - DAY keeps games whose local date equals today's local date.
 *  - MONTH keeps games whose local year-month equals the current year-month.
 *  - YEAR keeps games whose local year equals the current year.
 *  - ALL returns the list unchanged.
 *
 * Pure and Android-free: no Context, no IO. min SDK 26 guarantees java.time.
 */
fun filterByRange(
    games: List<GameRecord>,
    range: StatsRange,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<GameRecord> {
    if (range == StatsRange.ALL) return games

    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    return when (range) {
        StatsRange.DAY -> {
            val today = now.toLocalDate()
            games.filter {
                Instant.ofEpochMilli(it.createdAtEpochMs).atZone(zone).toLocalDate() == today
            }
        }
        StatsRange.MONTH -> {
            val thisMonth = YearMonth.from(now)
            games.filter {
                YearMonth.from(Instant.ofEpochMilli(it.createdAtEpochMs).atZone(zone)) == thisMonth
            }
        }
        StatsRange.YEAR -> {
            val thisYear = Year.from(now)
            games.filter {
                Year.from(Instant.ofEpochMilli(it.createdAtEpochMs).atZone(zone)) == thisYear
            }
        }
        StatsRange.ALL -> games
    }
}

/** Short label for a [StatsRange], used by the filter selector UI. */
fun StatsRange.label(): String = when (this) {
    StatsRange.DAY -> "Day"
    StatsRange.MONTH -> "Month"
    StatsRange.YEAR -> "Year"
    StatsRange.ALL -> "All"
}
