/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.updaterControllerEvents
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.repository.UpdaterRepository
import org.lineageos.updater.repository.UpdaterRepository.UpdateResult

sealed class FetchResult {
    data class Success(val hasNewUpdates: Boolean) : FetchResult()
    data object Error : FetchResult()
}

data class UpdateCheckState(
    val isRefreshing: Boolean = false,
    val fetchResult: FetchResult? = null,
    val lastCheckTimestamp: Long = -1L,
)

data class UpdaterUiState(
    val updates: List<UpdateInfo> = emptyList(),
    val hasLoaded: Boolean = false,
) {
    val updateIds: List<String> = updates.map { it.downloadId }
}

class UpdaterViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = UpdaterRepository(application)
    private val controller = UpdaterController.getInstance(application)

    private val _uiState = MutableStateFlow(UpdaterUiState())
    val uiState: StateFlow<UpdaterUiState> = _uiState.asStateFlow()
    val uiStateLiveData: LiveData<UpdaterUiState> = uiState.asLiveData()

    private val _updateCheckState = MutableStateFlow(
        UpdateCheckState(lastCheckTimestamp = repo.getLastCheckTimestamp())
    )
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState.asStateFlow()
    val updateCheckStateLiveData: LiveData<UpdateCheckState> = updateCheckState.asLiveData()

    init {
        // React to controller state changes without requiring the Activity to
        // forward broadcasts manually.
        viewModelScope.launch {
            application.updaterControllerEvents().collect { refreshUiState() }
        }
    }

    fun refreshUpdates() {
        if (_updateCheckState.value.isRefreshing) return
        viewModelScope.launch {
            _updateCheckState.update { it.copy(isRefreshing = true) }
            runCatching {
                repo.getUpdates().collect { applyResult(it) }
            }.onFailure {
                _updateCheckState.update { it.copy(fetchResult = FetchResult.Error) }
            }
            _updateCheckState.update { it.copy(isRefreshing = false) }
        }
    }

    fun consumeFetchResult() {
        _updateCheckState.update { it.copy(fetchResult = null) }
    }

    private fun refreshUiState() {
        _uiState.update {
            it.copy(
                updates = controller.updates.sortedByDescending { u -> u.timestamp },
                hasLoaded = true,
            )
        }
    }

    private fun applyResult(result: UpdateResult) {
        val onlineIds = result.updates.map { it.downloadId }
        result.updates.forEach { controller.addUpdate(it) }
        controller.setUpdatesAvailableOnline(onlineIds, true)

        _uiState.update { it ->
            it.copy(
                updates = controller.updates.sortedByDescending { it.timestamp },
                hasLoaded = true,
            )
        }

        if (!result.isStale) {
            _updateCheckState.update {
                it.copy(
                    fetchResult = FetchResult.Success(result.updates.isNotEmpty()),
                    lastCheckTimestamp = repo.getLastCheckTimestamp(),
                )
            }
        }
    }
}
