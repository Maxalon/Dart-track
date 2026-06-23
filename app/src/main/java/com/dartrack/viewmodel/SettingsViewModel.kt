package com.dartrack.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dartrack.data.Settings
import com.dartrack.data.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Exposes the [SettingsRepository] StateFlow to Compose and an [update] entry
 * point that persists changes on [viewModelScope]. Mirrors [AppViewModel]'s
 * thin repo-wrapping shape.
 */
class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val settings = repo.settings

    /** Apply [transform], sanitize + persist via the repository (fire-and-forget). */
    fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { repo.update(transform) }
    }

    class Factory(private val repo: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repo) as T
    }
}
