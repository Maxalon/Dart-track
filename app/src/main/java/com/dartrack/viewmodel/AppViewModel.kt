package com.dartrack.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dartrack.data.GameRepository

class AppViewModel(private val repo: GameRepository) : ViewModel() {
    val games = repo.games

    class Factory(private val repo: GameRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(repo) as T
    }
}
