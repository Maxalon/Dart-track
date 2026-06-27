package com.dartrack.feedback

import com.dartrack.model.GameState
import com.dartrack.model.KillerState
import com.dartrack.model.X01State

/**
 * Pure mapping from a game-state transition to the [FeedbackEvent] that should
 * be played, or null for "no sound". This is the single source of truth used by
 * [GameFeedback] from the game view-model's mutate path, so EVERY mode (and the
 * CPU's auto-played visits) get consistent audio/haptic feedback from one place.
 *
 * It is deliberately pure and Android-free so it can be unit-tested on the JVM.
 * Detection is conservative: anything it can't classify falls back to a soft
 * [FeedbackEvent.SCORE_CONFIRM], and an unchanged state produces null.
 *
 * Mode-specific cues:
 *  - X01: a completed leg that ends the match -> GAME_WIN; a completed leg that
 *    doesn't -> LEG_WIN; an entered 180 -> BIG_SCORE; a bust -> BUST.
 *  - Killer: a visit that drains any lives (without finishing) -> LIFE_LOST.
 *  - Any mode: a transition into a finished game -> GAME_WIN.
 */
fun feedbackEventFor(old: GameState?, new: GameState): FeedbackEvent? {
    if (old == null) return null
    if (old === new) return null

    // ---- X01: leg/match, 180 and bust cues from the just-acted turn. --------
    if (old is X01State && new is X01State) {
        if (new.completedLegs.size > old.completedLegs.size) {
            return if (new.isFinished) FeedbackEvent.GAME_WIN else FeedbackEvent.LEG_WIN
        }
        if (new.isFinished && !old.isFinished) return FeedbackEvent.GAME_WIN
        val acted = old.currentPlayerIndex
        val beforeTurns = old.perPlayer.getOrNull(acted)?.turns?.size ?: 0
        val afterTurns = new.perPlayer.getOrNull(acted)?.turns?.size ?: 0
        if (afterTurns > beforeTurns) {
            val last = new.perPlayer[acted].turns.last()
            if (last.bust) return FeedbackEvent.BUST
            if (last.entered == 180) return FeedbackEvent.BIG_SCORE
        }
        return FeedbackEvent.SCORE_CONFIRM
    }

    // ---- Killer: a visit that removes lives reads as a "life lost" cue. ------
    if (old is KillerState && new is KillerState) {
        if (new.isFinished && !old.isFinished) return FeedbackEvent.GAME_WIN
        val before = old.perPlayer.sumOf { it.lives }
        val after = new.perPlayer.sumOf { it.lives }
        if (after < before) return FeedbackEvent.LIFE_LOST
        return FeedbackEvent.SCORE_CONFIRM
    }

    // ---- Every other mode: win fanfare on finish, else a confirm click. -----
    if (new.isFinished && !old.isFinished) return FeedbackEvent.GAME_WIN
    return FeedbackEvent.SCORE_CONFIRM
}
