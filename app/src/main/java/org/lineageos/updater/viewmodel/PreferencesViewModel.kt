/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.lineageos.updater.misc.Constants.CheckInterval
import org.lineageos.updater.repository.PreferencesRepository

class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferencesRepository(application)

    val preferencesData = repository.preferencesData

    fun setPeriodicCheckEnabled(enabled: Boolean) = repository.setPeriodicCheckEnabled(enabled)
    fun setPeriodicCheckInterval(interval: CheckInterval) =
        repository.setPeriodicCheckInterval(interval)
    fun setAutoDeleteUpdates(enabled: Boolean) = repository.setAutoDeleteUpdates(enabled)
    fun setMeteredNetworkWarning(enabled: Boolean) = repository.setMeteredNetworkWarning(enabled)
    fun setAbPerfMode(enabled: Boolean) = repository.setAbPerfMode(enabled)
    fun setAbStreamingMode(enabled: Boolean) = repository.setAbStreamingMode(enabled)
    fun setUpdateRecovery(enabled: Boolean) = repository.setUpdateRecovery(enabled)
}
