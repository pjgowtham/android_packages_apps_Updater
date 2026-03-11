/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.preferences

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.lineageos.updater.data.CheckInterval
import org.lineageos.updater.data.PreferencesRepository
import org.lineageos.updater.worker.UpdateCheckWorker

data class PreferencesUiState(
    val periodicCheckEnabled: Boolean,
    val checkInterval: Int,
    val autoDelete: Boolean,
    val meteredWarning: Boolean,
    val abPerfMode: Boolean,
    val recoveryUpdate: Boolean,
)

class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferencesRepository(application)

    private val _uiState = MutableStateFlow(
        PreferencesUiState(
            periodicCheckEnabled = repository.getPeriodicCheckEnabled(),
            checkInterval = repository.getCheckInterval().id,
            autoDelete = repository.getAutoDelete(),
            meteredWarning = repository.getMeteredWarning(),
            abPerfMode = repository.getAbPerfMode(),
            recoveryUpdate = repository.getRecoveryUpdate(),
        )
    )
    val uiState: StateFlow<PreferencesUiState> = _uiState.asStateFlow()

    fun setPeriodicCheckEnabled(value: Boolean) {
        repository.setPeriodicCheckEnabled(value)
        _uiState.update { it.copy(periodicCheckEnabled = value) }
        if (value) {
            UpdateCheckWorker.schedulePeriodicCheck(
                getApplication(),
                repository.getCheckInterval(),
            )
        } else {
            UpdateCheckWorker.cancelPeriodicCheck(getApplication())
        }
    }

    fun setCheckInterval(id: Int) {
        val interval = CheckInterval.fromId(id) ?: return
        repository.setCheckInterval(interval)
        _uiState.update { it.copy(checkInterval = id) }
        if (_uiState.value.periodicCheckEnabled) {
            UpdateCheckWorker.schedulePeriodicCheck(
                getApplication(),
                interval,
                replaceExisting = true,
            )
        }
    }

    fun setAutoDelete(value: Boolean) {
        repository.setAutoDelete(value)
        _uiState.update { it.copy(autoDelete = value) }
    }

    fun setMeteredWarning(value: Boolean) {
        repository.setMeteredWarning(value)
        _uiState.update { it.copy(meteredWarning = value) }
    }

    fun setAbPerfMode(value: Boolean) {
        repository.setAbPerfMode(value)
        _uiState.update { it.copy(abPerfMode = value) }
    }

    fun setRecoveryUpdate(value: Boolean) {
        repository.setRecoveryUpdate(value)
        _uiState.update { it.copy(recoveryUpdate = value) }
    }
}
