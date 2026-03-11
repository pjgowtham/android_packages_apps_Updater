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
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UpdaterRepository
import org.lineageos.updater.data.UpdaterRepository.UpdateResult

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
    val updates: List<Update> = emptyList(),
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

    private fun applyResult(result: UpdateResult) {
        val onlineIds = result.updates.map { it.downloadId }
        val hasNewUpdates = result.updates.any { controller.addUpdate(it) }
        controller.setUpdatesAvailableOnline(onlineIds, true)

        _uiState.update { state ->
            state.copy(
                updates = controller.updates.sortedByDescending { it.timestamp },
            )
        }

        if (!result.isStale) {
            _updateCheckState.update {
                it.copy(
                    fetchResult = FetchResult.Success(hasNewUpdates),
                    lastCheckTimestamp = repo.getLastCheckTimestamp(),
                )
            }
        }
    }
}
