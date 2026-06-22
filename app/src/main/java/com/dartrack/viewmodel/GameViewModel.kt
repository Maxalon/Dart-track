package com.dartrack.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dartrack.data.GameRecord
import com.dartrack.data.GameRepository
import com.dartrack.model.GameState
import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CountUpState
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CricketState
import com.dartrack.model.HalfItState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    init {
        viewModelScope.launch {
            repo.games.collect { list ->
                val r = list.firstOrNull { it.id == recordId }
                if (_record.value == null) _record.value = r
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

    class Factory(
        private val repo: GameRepository,
        private val recordId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GameViewModel(repo, recordId) as T
    }
}
