package com.dartrack.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dartrack.data.GameRecord
import com.dartrack.data.GameRepository
import com.dartrack.model.GameState
import com.dartrack.model.cricket.CricketState
import com.dartrack.model.halfit.HalfItState
import com.dartrack.model.x01.X01State
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

    class Factory(
        private val repo: GameRepository,
        private val recordId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GameViewModel(repo, recordId) as T
    }
}
