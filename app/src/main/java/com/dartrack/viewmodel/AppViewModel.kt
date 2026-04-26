package com.dartrack.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dartrack.data.GameRecord
import com.dartrack.data.GameRepository
import com.dartrack.model.CricketState
import com.dartrack.model.HalfItState
import com.dartrack.model.X01State
import kotlinx.coroutines.launch
import java.util.UUID

class AppViewModel(private val repo: GameRepository) : ViewModel() {
    val games = repo.games

    /**
     * Create a fresh game with the same mode/players/settings as [record],
     * persist it, and call [onCreated] with its id so the caller can
     * navigate.
     */
    fun rematch(record: GameRecord, onCreated: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val newState = when (val s = record.state) {
            is X01State -> X01State.new(s.players, s.startScore, s.doubleOut)
            is CricketState -> CricketState.new(s.players)
            is HalfItState -> HalfItState.new(s.players)
        }
        val newRecord = record.copy(
            id = UUID.randomUUID().toString(),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            state = newState,
        )
        viewModelScope.launch {
            repo.upsert(newRecord)
            onCreated(newRecord.id)
        }
    }

    class Factory(private val repo: GameRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(repo) as T
    }
}
