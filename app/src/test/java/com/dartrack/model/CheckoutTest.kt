package com.dartrack.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutTest {

    /** Numeric value of a single dart segment label. */
    private fun valueOf(label: String): Int = when {
        label == "Bull" -> 50
        label == "25" -> 25
        label.startsWith("T") -> label.drop(1).toInt() * 3
        label.startsWith("D") -> label.drop(1).toInt() * 2
        else -> label.toInt() // plain single
    }

    private fun sumOf(route: String): Int =
        route.split(" ").sumOf { valueOf(it) }

    private fun isDoubleFinisher(label: String): Boolean =
        label == "Bull" || label.startsWith("D")

    @Test
    fun checkout170_endsBull_andValid() {
        val routes = Checkout.suggest(170, doubleOut = true)
        assertTrue(routes.isNotEmpty())
        val first = routes.first()
        assertEquals("T20 T20 Bull", first)
        assertEquals(170, sumOf(first))
        assertTrue(first.split(" ").last() == "Bull")
    }

    @Test
    fun checkout167() {
        val routes = Checkout.suggest(167, doubleOut = true)
        assertTrue(routes.isNotEmpty())
        assertEquals("T20 T19 Bull", routes.first())
        assertEquals(167, sumOf(routes.first()))
    }

    @Test
    fun checkout100_isT20D20() {
        val routes = Checkout.suggest(100, doubleOut = true)
        assertEquals("T20 D20", routes.first())
        assertEquals(100, sumOf(routes.first()))
    }

    @Test
    fun checkout40_isD20() {
        val routes = Checkout.suggest(40, doubleOut = true)
        assertEquals("D20", routes.first())
        assertEquals(40, sumOf(routes.first()))
    }

    @Test
    fun checkout2_isD1() {
        val routes = Checkout.suggest(2, doubleOut = true)
        assertEquals("D1", routes.first())
        assertEquals(2, sumOf(routes.first()))
    }

    @Test
    fun checkout50_containsBull() {
        val routes = Checkout.suggest(50, doubleOut = true)
        assertTrue(routes.isNotEmpty())
        assertTrue(routes.contains("Bull"))
        assertEquals(50, sumOf(routes.first()))
    }

    @Test
    fun checkout57_isValid() {
        val routes = Checkout.suggest(57, doubleOut = true)
        assertTrue(routes.isNotEmpty())
        // Every route must sum to 57 and end on a double.
        routes.forEach { route ->
            assertEquals(57, sumOf(route))
            assertTrue(isDoubleFinisher(route.split(" ").last()))
        }
    }

    @Test
    fun impossibleDoubleOutCheckouts_areEmpty() {
        // Bogey numbers and out-of-range values.
        listOf(169, 168, 166, 165, 163, 162, 159, 171, 200, 1, 0).forEach {
            assertTrue("expected empty for $it", Checkout.suggest(it, doubleOut = true).isEmpty())
        }
    }

    @Test
    fun nonDoubleOut_rangeGuards() {
        assertTrue(Checkout.suggest(0, doubleOut = false).isEmpty())
        assertTrue(Checkout.suggest(181, doubleOut = false).isEmpty())
        assertFalse(Checkout.suggest(180, doubleOut = false).isEmpty())
        assertFalse(Checkout.suggest(1, doubleOut = false).isEmpty())
    }

    @Test
    fun allDoubleOutRoutes_endOnDoubleOrBull_andSumCorrectly() {
        for (remaining in 2..170) {
            val routes = Checkout.suggest(remaining, doubleOut = true)
            routes.forEach { route ->
                val darts = route.split(" ")
                assertTrue(
                    "route '$route' for $remaining must have <=3 darts",
                    darts.size <= 3,
                )
                assertTrue(
                    "route '$route' for $remaining must end on double/Bull",
                    isDoubleFinisher(darts.last()),
                )
                assertEquals(
                    "route '$route' must sum to $remaining",
                    remaining,
                    sumOf(route),
                )
            }
        }
    }

    @Test
    fun nonDoubleOutRoutes_sumCorrectly_andWithin3Darts() {
        // Totals that cannot be made with any 3 dartboard segments, even
        // without a double-out requirement. The engine correctly returns empty
        // for these.
        val unreachable = setOf(163, 166, 169, 172, 173, 175, 176, 178, 179)
        for (remaining in 1..180) {
            val routes = Checkout.suggest(remaining, doubleOut = false)
            if (remaining in unreachable) {
                assertTrue("expected empty for unreachable $remaining", routes.isEmpty())
                continue
            }
            assertTrue("expected a route for $remaining", routes.isNotEmpty())
            routes.forEach { route ->
                val darts = route.split(" ")
                assertTrue(darts.size <= 3)
                assertEquals(remaining, sumOf(route))
            }
        }
    }

    @Test
    fun returnsAtMostThreeRoutes() {
        for (remaining in 2..170) {
            assertTrue(Checkout.suggest(remaining, doubleOut = true).size <= 3)
        }
    }
}
