package com.dartrack.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dartrack.data.GameRecord
import com.dartrack.data.GameRepository
import com.dartrack.model.Checkout
import com.dartrack.model.GamePlayer
import com.dartrack.model.GameState
import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BaseballState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CountUpState
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CricketState
import com.dartrack.model.GolfResult
import com.dartrack.model.GolfState
import com.dartrack.model.GotchaState
import com.dartrack.model.HalfItState
import com.dartrack.model.KillerState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import com.dartrack.model.bot.BotLevel
import com.dartrack.model.bot.DartsBot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ViewModel scoped to a single game record. Holds the live state and pushes
 * every change back to the repository so persistence stays current.
 */
class GameViewModel(
    private val repo: GameRepository,
    private val recordId: String,
) : ViewModel() {

    private val _record = MutableStateFlow<GameRecord?>(null)
    val record: StateFlow<GameRecord?> = _record.asStateFlow()

    /**
     * The in-flight CPU auto-play coroutine (see [maybeStartBotTurns]). At most
     * one runs at a time: every trigger cancels the previous one before starting
     * a fresh one, so a human turn (or undo) that lands mid-bot-think can never
     * be raced by a stale bot move.
     */
    private var botJob: Job? = null

    init {
        viewModelScope.launch {
            repo.games.collect { list ->
                val r = list.firstOrNull { it.id == recordId }
                if (_record.value == null) {
                    _record.value = r
                    // First load may already sit on a CPU seat (resumed game).
                    maybeStartBotTurns()
                }
            }
        }
    }

    private fun mutate(transform: (GameState) -> GameState) {
        val cur = _record.value ?: return
        val newState = transform(cur.state)
        val updated = cur.copy(
            state = newState,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        _record.value = updated
        viewModelScope.launch { repo.upsert(updated) }
        // Every applied turn (human or bot) may hand the turn to a CPU seat.
        maybeStartBotTurns()
    }

    // ---------------------------------------------------------------- CPU auto-play

    /**
     * Drive any consecutive CPU turns automatically. Cancels any in-flight bot
     * job first (race guard: a human turn or undo invalidates a pending bot
     * move), then — only when the current seat is a bot and the game is live —
     * launches a single coroutine that keeps taking bot turns, each after a short
     * UX delay, until it is a human's turn or the game ends.
     *
     * Each bot visit is computed from the CURRENT loaded state and fed through
     * the SAME apply path a human turn uses ([applyX01Turn] / [applyCountUpTurn]),
     * so the bot is subject to identical rules, persistence and undo. Re-reading
     * the state from [_record] every iteration keeps it consistent if the model
     * snapshot changed under us.
     */
    private fun maybeStartBotTurns() {
        botJob?.cancel()
        if (!currentSeatIsBot()) return
        botJob = viewModelScope.launch {
            // Keep going while it is a live bot seat AND we weren't cancelled.
            while (isActive && currentSeatIsBot()) {
                delay(BOT_TURN_DELAY_MS)
                if (!isActive) return@launch
                // Re-check after the delay: a human action may have intervened.
                if (!playOneBotTurn()) return@launch
            }
        }
    }

    /** True when the loaded game is live and the current seat is a CPU player. */
    private fun currentSeatIsBot(): Boolean {
        val state = _record.value?.state ?: return false
        if (state.isFinished) return false
        return currentSeat(state)?.isBot == true
    }

    private fun currentSeat(state: GameState): GamePlayer? =
        state.players.getOrNull(state.currentPlayerIndex)

    /**
     * Compute and apply exactly one bot visit for the current X01/Count-Up seat.
     * Returns false if there is nothing to do (no live bot seat, or a mode the
     * CPU doesn't play), which ends the loop. The RNG is seeded from the clock so
     * successive visits vary; determinism isn't required in live play (the model
     * tests cover the seeded path).
     */
    private fun playOneBotTurn(): Boolean {
        val state = _record.value?.state ?: return false
        if (state.isFinished) return false
        val seat = currentSeat(state) ?: return false
        if (!seat.isBot) return false
        val level = seat.botLevel ?: BotLevel.MEDIUM
        val bot = DartsBot(level, Random(System.nanoTime()))
        return when (state) {
            is X01State -> {
                val remaining = state.currentPlayerScore()
                val visit = bot.x01Visit(remaining, state.doubleOut)
                // The bot returns exactly `remaining` only when it intends to
                // check out, and only from a finishable remaining — flag that as
                // a double finish so the engine accepts it (matches the screens'
                // finish handling and the bot's own contract).
                val finishes = visit == remaining &&
                    Checkout.suggest(remaining, state.doubleOut).isNotEmpty()
                applyX01Turn(visit, finishedOnDouble = finishes || !state.doubleOut)
                true
            }
            is CountUpState -> {
                applyCountUpTurn(bot.countUpVisit())
                true
            }
            // CPU seats are only offered for X01 / Count-Up; other modes never
            // carry a bot seat, so there is nothing to auto-play.
            else -> false
        }
    }

    fun applyX01Turn(entered: Int, finishedOnDouble: Boolean) =
        mutate { (it as X01State).applyTurn(entered, finishedOnDouble) }

    fun undoX01() = mutate { (it as X01State).undoLast() }

    fun applyCricketTurn(marksByTarget: Map<Int, Int>) =
        mutate { (it as CricketState).applyTurn(marksByTarget) }

    fun undoCricket() = mutate { (it as CricketState).undoLast() }

    fun applyHalfItTurn(points: Int) =
        mutate { (it as HalfItState).applyTurn(points) }

    fun undoHalfIt() = mutate { (it as HalfItState).undoLast() }

    fun applyAroundClockTurn(hits: Int) =
        mutate { (it as AroundTheClockState).applyTurn(hits) }

    fun undoAroundClock() = mutate { (it as AroundTheClockState).undoLast() }

    fun applyBobs27Turn(hits: Int) =
        mutate { (it as BobsTwentySevenState).applyTurn(hits) }

    fun undoBobs27() = mutate { (it as BobsTwentySevenState).undoLast() }

    fun applyShanghaiTurn(singles: Int, doubles: Int, triples: Int) =
        mutate { (it as ShanghaiState).applyTurn(singles, doubles, triples) }

    fun undoShanghai() = mutate { (it as ShanghaiState).undoLast() }

    fun applyCatch40Turn(hits: Int) =
        mutate { (it as Catch40State).applyTurn(hits) }

    fun undoCatch40() = mutate { (it as Catch40State).undoLast() }

    fun applyCountUpTurn(total: Int) =
        mutate { (it as CountUpState).applyTurn(total) }

    fun undoCountUp() = mutate { (it as CountUpState).undoLast() }
    fun applyCheckoutAttempt(hit: Boolean, darts: Int) =
        mutate { (it as CheckoutTrainerState).applyAttempt(hit, darts) }

    fun undoCheckout() = mutate { (it as CheckoutTrainerState).undoLast() }

    fun applyBaseballTurn(singles: Int, doubles: Int, triples: Int) =
        mutate { (it as BaseballState).applyTurn(singles, doubles, triples) }

    fun undoBaseball() = mutate { (it as BaseballState).undoLast() }

    fun applyGolfResult(result: GolfResult) =
        mutate { (it as GolfState).applyResult(result) }

    fun undoGolf() = mutate { (it as GolfState).undoLast() }

    fun applyGotchaTurn(total: Int) =
        mutate { (it as GotchaState).applyTurn(total) }

    fun undoGotcha() = mutate { (it as GotchaState).undoLast() }

    fun applyKillerTurn(hits: List<Int>) = mutate { (it as KillerState).applyTurn(hits) }

    fun undoKiller() = mutate { (it as KillerState).undoLast() }

    class Factory(
        private val repo: GameRepository,
        private val recordId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GameViewModel(repo, recordId) as T
    }

    private companion object {
        /** UX pause before a CPU seat plays its visit, so it reads as a "throw". */
        const val BOT_TURN_DELAY_MS = 900L
    }
}
