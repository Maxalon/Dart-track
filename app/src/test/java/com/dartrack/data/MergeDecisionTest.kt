package com.dartrack.data

import com.dartrack.model.Player
import com.dartrack.net.FriendEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [mergeDecision]: "Merge players" may only silently merge two local guests.
 * A guest into a friend goes through the logged transfer; every other merge
 * into or out of an account-linked player is refused.
 */
class MergeDecisionTest {

    private val me = Player("p-me", "Max", accountId = "uMe")
    private val friend = Player("p-friend", "Anna", accountId = "uF")
    private val exFriend = Player("p-x", "Xavier", accountId = "uX")
    private val hidden = Player("p-h", "Stranger", accountId = "uS", hidden = true)
    private val guest = Player("p-g", "Gary")
    private val guest2 = Player("p-g2", "Gus")
    private val anna = FriendEntry("uF", "anna")
    private val friends = listOf(anna)

    @Test
    fun guestIntoGuest_isAPlainMerge() {
        assertEquals(MergeDecision.Allowed, mergeDecision(guest, guest2, friends))
        assertEquals(MergeDecision.Allowed, mergeDecision(guest, guest2, emptyList()), "logged out too")
    }

    @Test
    fun guestIntoFriend_becomesTheTransfer() {
        assertEquals(MergeDecision.Transfer(anna), mergeDecision(guest, friend, friends))
    }

    @Test
    fun guestIntoMeHiddenOrNonFriendAccount_isBlocked() {
        assertIs<MergeDecision.Blocked>(mergeDecision(guest, me, friends))
        assertIs<MergeDecision.Blocked>(mergeDecision(guest, hidden, friends))
        assertIs<MergeDecision.Blocked>(mergeDecision(guest, exFriend, friends))
        // A friend's player while logged out (no friends list) can't take a transfer.
        assertIs<MergeDecision.Blocked>(mergeDecision(guest, friend, emptyList()))
    }

    @Test
    fun anAccountPlayerAsSource_isAlwaysBlocked() {
        for (source in listOf(me, friend, exFriend, hidden)) {
            assertIs<MergeDecision.Blocked>(mergeDecision(source, guest, friends), source.name)
        }
        assertIs<MergeDecision.Blocked>(mergeDecision(friend, me, friends), "account -> account")
        assertIs<MergeDecision.Blocked>(mergeDecision(me, friend, friends), "account -> account")
    }
}
