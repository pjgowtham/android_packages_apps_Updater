/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.preferences

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.lineageos.updater.data.CheckInterval
import org.lineageos.updater.data.PreferencesRepository
import org.lineageos.updater.worker.UpdatesCheckWorker

data class PreferencesUiState(
    val periodicCheckEnabled: Boolean = true,
    val checkIntervalId: Int = CheckInterval.default.id,
    val autoDelete: Boolean = true,
    val meteredNetworkWarning: Boolean = true,
    val abPerfMode: Boolean = false,
    val recoveryUpdateEnabled: Boolean = false,
)

class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferencesRepository(application)

    val uiState: StateFlow<PreferencesUiState> = combine(
        repository.periodicCheckEnabledFlow,
        repository.checkIntervalFlow,
        repository.autoDeleteFlow,
        repository.meteredNetworkWarningFlow,
        repository.abPerfModeFlow,
    ) { periodicEnabled, interval, autoDelete, meteredWarning, abPerfMode ->
        PreferencesUiState(
            periodicCheckEnabled = periodicEnabled,
            checkIntervalId = interval.id,
            autoDelete = autoDelete,
            meteredNetworkWarning = meteredWarning,
            abPerfMode = abPerfMode,
            recoveryUpdateEnabled = repository.recoveryUpdateEnabled,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PreferencesUiState(recoveryUpdateEnabled = repository.recoveryUpdateEnabled),
    )

    fun setPeriodicCheckEnabled(value: Boolean) {
        viewModelScope.launch {
            repository.setPeriodicCheckEnabled(value)
            if (value) {
                UpdatesCheckWorker.schedulePeriodicCheck(getApplication())
            } else {
                UpdatesCheckWorker.cancelPeriodicCheck(getApplication())
            }
        }
    }

    fun setCheckInterval(id: Int) {
        val interval = CheckInterval.fromId(id) ?: return
        viewModelScope.launch {
            repository.setCheckInterval(interval)
            if (repository.isPeriodicUpdateCheckEnabled()) {
                UpdatesCheckWorker.reschedulePeriodicCheck(getApplication())
            }
        }
    }

    fun setAutoDelete(value: Boolean) {
        viewModelScope.launch { repository.setAutoDelete(value) }
    }

    fun setMeteredNetworkWarning(value: Boolean) {
        viewModelScope.launch { repository.setMeteredNetworkWarning(value) }
    }

    fun setAbPerfMode(value: Boolean) {
        viewModelScope.launch { repository.setAbPerfMode(value) }
    }

    fun setRecoveryUpdate(value: Boolean) {
        repository.setRecoveryUpdate(value)
    }
}
