package com.dartrack.model

/**
 * Pure, dependency-free X01 checkout suggestion engine.
 *
 * [suggest] returns up to 3 human-readable checkout routes for finishing
 * exactly [remaining] with three darts or fewer.
 *
 * Dart notation:
 *  - singles S1..S20  -> just the number, e.g. "20"
 *  - doubles D1..D20  -> "D20"
 *  - triples T1..T20  -> "T20"
 *  - outer bull (25)  -> "25"
 *  - inner bull (50)  -> "Bull"
 *
 * When [doubleOut] is true every route MUST end on a double or "Bull".
 * Routes are returned as dart notation joined by spaces, e.g. "T20 T20 D20".
 */
object Checkout {

    /** A throwable segment: its display label and point value. */
    private data class Seg(val label: String, val value: Int)

    /** Whether the segment is a valid "finisher" under double-out rules. */
    private fun Seg.isDouble(): Boolean =
        label == "Bull" || label.startsWith("D")

    // All segments that can be thrown by a single dart, ordered so that the
    // "preferred" ones come first (high triples, then bull, then high
    // singles/doubles). This ordering drives which routes surface first.
    private val singles: List<Seg> =
        (20 downTo 1).map { Seg(it.toString(), it) }
    private val doubles: List<Seg> =
        (20 downTo 1).map { Seg("D$it", it * 2) }
    private val triples: List<Seg> =
        (20 downTo 1).map { Seg("T$it", it * 3) }
    private val bullSingle = Seg("25", 25)
    private val bullDouble = Seg("Bull", 50)

    // Setup darts (non-final). Order biases toward classic pro play: T20 first,
    // big triples, the bull, then high singles, then doubles.
    private val setupDarts: List<Seg> =
        triples + listOf(bullDouble, bullSingle) + singles + doubles

    // Finishing darts under double-out: must be a double or Bull. Bull (50) and
    // D20 (40) are the most common pro finishes, so order high-to-low with Bull
    // placed by its value (50, then D20=40 ... D1=2).
    private val finishDoublesByValue: List<Seg> =
        (listOf(bullDouble) + doubles).sortedByDescending { it.value }

    // Finishing darts when double-out is OFF: anything that hits the number.
    private val finishAnyByValue: List<Seg> =
        (triples + doubles + singles + listOf(bullDouble, bullSingle))
            .sortedByDescending { it.value }

    fun suggest(remaining: Int, doubleOut: Boolean): List<String> {
        // Range guards.
        if (doubleOut) {
            if (remaining < 2 || remaining > 170) return emptyList()
        } else {
            if (remaining < 1 || remaining > 180) return emptyList()
        }

        // The canonical pro route (if any) is always pinned first. Everything
        // else is collected by an exhaustive shortest-first search.
        val pinned: String? = if (doubleOut) STANDARD[remaining] else null

        val extras = LinkedHashSet<String>()
        val finishers = if (doubleOut) finishDoublesByValue else finishAnyByValue

        // 1) One-dart finishes.
        for (f in finishers) {
            if (f.value == remaining) extras.add(f.label)
        }

        // 2) Two-dart finishes: setup + finisher.
        if (extras.size < 4) {
            for (s in setupDarts) {
                val rest = remaining - s.value
                if (rest <= 0) continue
                for (f in finishers) {
                    if (f.value == rest) {
                        extras.add("${s.label} ${f.label}")
                        break // best (highest) finisher for this setup is enough
                    }
                }
                if (extras.size >= 4) break
            }
        }

        // 3) Three-dart finishes: setup + setup + finisher.
        if (extras.size < 4) {
            outer@ for (s1 in setupDarts) {
                if (s1.value >= remaining) continue
                for (s2 in setupDarts) {
                    val rest = remaining - s1.value - s2.value
                    if (rest <= 0) continue
                    for (f in finishers) {
                        if (f.value == rest) {
                            extras.add("${s1.label} ${s2.label} ${f.label}")
                            break
                        }
                    }
                    if (extras.size >= 4) break@outer
                }
            }
        }

        // Order non-pinned routes shortest-first so the surfaced suggestions are
        // the simplest valid finishes.
        val ordered = LinkedHashSet<String>()
        pinned?.let { ordered.add(it) }
        extras.sortedBy { it.count { c -> c == ' ' } }.forEach { ordered.add(it) }

        return ordered.take(3)
    }

    /**
     * Canonical pro checkout routes for the common cases. These are surfaced
     * first when present. Only includes routes a serious player would actually
     * use; the search algorithm fills in everything else.
     */
    private val STANDARD: Map<Int, String> = mapOf(
        170 to "T20 T20 Bull",
        167 to "T20 T19 Bull",
        164 to "T20 T18 Bull",
        161 to "T20 T17 Bull",
        160 to "T20 T20 D20",
        158 to "T20 T20 D19",
        157 to "T20 T19 D20",
        156 to "T20 T20 D18",
        155 to "T20 T19 D19",
        154 to "T20 T18 D20",
        153 to "T20 T19 D18",
        152 to "T20 T20 D16",
        151 to "T20 T17 D20",
        150 to "T20 T18 D18",
        149 to "T20 T19 D16",
        148 to "T20 T20 D14",
        147 to "T20 T17 D18",
        146 to "T20 T18 D16",
        145 to "T20 T19 D14",
        144 to "T20 T20 D12",
        143 to "T20 T17 D16",
        142 to "T20 T14 D20",
        141 to "T20 T19 D12",
        140 to "T20 T20 D10",
        139 to "T20 T13 D20",
        138 to "T20 T18 D12",
        137 to "T20 T19 D10",
        136 to "T20 T20 D8",
        135 to "T20 T17 D12",
        134 to "T20 T14 D16",
        133 to "T20 T19 D8",
        132 to "T20 T16 D12",
        131 to "T20 T13 D16",
        130 to "T20 T18 D8",
        129 to "T19 T16 D12",
        128 to "T18 T14 D16",
        127 to "T20 T17 D8",
        126 to "T19 T19 D6",
        125 to "T20 T19 D4",
        124 to "T20 T16 D8",
        123 to "T19 T16 D9",
        122 to "T18 T20 D4",
        121 to "T20 T11 D14",
        120 to "T20 20 D20",
        119 to "T19 T10 D16",
        118 to "T20 18 D20",
        117 to "T20 17 D20",
        116 to "T20 16 D20",
        115 to "T20 15 D20",
        114 to "T20 14 D20",
        113 to "T20 13 D20",
        112 to "T20 12 D20",
        111 to "T20 19 D16",
        110 to "T20 Bull",
        109 to "T20 17 D16",
        108 to "T20 16 D16",
        107 to "T19 18 D16",
        106 to "T20 14 D16",
        105 to "T20 13 D16",
        104 to "T18 18 D16",
        103 to "T19 10 D18",
        102 to "T20 10 D16",
        101 to "T17 18 D16",
        100 to "T20 D20",
        99 to "T19 10 D16",
        98 to "T20 D19",
        97 to "T19 D20",
        96 to "T20 D18",
        95 to "T19 D19",
        94 to "T18 D20",
        93 to "T19 D18",
        92 to "T20 D16",
        91 to "T17 D20",
        90 to "T18 D18",
        89 to "T19 D16",
        88 to "T16 D20",
        87 to "T17 D18",
        86 to "T18 D16",
        85 to "T15 D20",
        84 to "T20 D12",
        83 to "T17 D16",
        82 to "T14 D20",
        81 to "T19 D12",
        80 to "T20 D10",
        79 to "T19 D11",
        78 to "T18 D12",
        77 to "T19 D10",
        76 to "T20 D8",
        75 to "T17 D12",
        74 to "T14 D16",
        73 to "T19 D8",
        72 to "T16 D12",
        71 to "T13 D16",
        70 to "T18 D8",
        69 to "T19 D6",
        68 to "T20 D4",
        67 to "T17 D8",
        66 to "T10 D18",
        65 to "T19 D4",
        64 to "T16 D8",
        63 to "T13 D12",
        62 to "T10 D16",
        61 to "T15 D8",
        60 to "20 D20",
        59 to "19 D20",
        58 to "18 D20",
        57 to "17 D20",
        56 to "16 D20",
        55 to "15 D20",
        54 to "14 D20",
        53 to "13 D20",
        52 to "20 D16",
        51 to "19 D16",
        50 to "Bull",
        49 to "17 D16",
        48 to "16 D16",
        47 to "15 D16",
        46 to "6 D20",
        45 to "13 D16",
        44 to "12 D16",
        43 to "11 D16",
        42 to "10 D16",
        41 to "9 D16",
        40 to "D20",
        39 to "7 D16",
        38 to "D19",
        37 to "5 D16",
        36 to "D18",
        35 to "3 D16",
        34 to "D17",
        33 to "1 D16",
        32 to "D16",
        31 to "15 D8",
        30 to "D15",
        29 to "13 D8",
        28 to "D14",
        27 to "11 D8",
        26 to "D13",
        25 to "9 D8",
        24 to "D12",
        23 to "7 D8",
        22 to "D11",
        21 to "5 D8",
        20 to "D10",
        19 to "3 D8",
        18 to "D9",
        17 to "1 D8",
        16 to "D8",
        15 to "7 D4",
        14 to "D7",
        13 to "5 D4",
        12 to "D6",
        11 to "3 D4",
        10 to "D5",
        9 to "1 D4",
        8 to "D4",
        7 to "3 D2",
        6 to "D3",
        5 to "1 D2",
        4 to "D2",
        3 to "1 D1",
        2 to "D1",
    )
}
