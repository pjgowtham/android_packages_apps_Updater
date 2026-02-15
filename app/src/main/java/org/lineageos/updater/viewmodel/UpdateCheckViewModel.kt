/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.lineageos.updater.R
import org.lineageos.updater.UpdatesCheckReceiver
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.data.UpdateCheckRepository
import org.lineageos.updater.model.UpdateInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Manages fetching, caching, and exposing the list of available system updates. */
class UpdateCheckViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface UiEvent {
        /**
         * Events corresponding to one-shot UI actions, like showing a toast.
         *
         * @property messageId The resource ID of the message to display.
         * @property long Whether to show the message for a long duration.
         */
        data class ShowMessage(
            @param:StringRes val messageId: Int,
            val long: Boolean = false,
        ) : UiEvent
    }

    data class UiState(
        /** List of download IDs sorted by timestamp (newest first). */
        val updateIds: List<String> = emptyList(),
        /** Whether a check for updates is currently in progress. */
        val isCheckingForUpdates: Boolean = false,
        /** Unix timestamp (ms) of the last successful server check. -1 if never checked. */
        val lastCheckTimestamp: Long = -1L,
    )

    private val repository = UpdateCheckRepository(application)

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent: Flow<UiEvent> = _uiEvent.receiveAsFlow()

    private val _uiState = MutableStateFlow(
        UiState(
            lastCheckTimestamp = repository.getLastCheckTimestamp(),
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _updaterController = MutableStateFlow<UpdaterController?>(null)
    val updaterController: StateFlow<UpdaterController?> = _updaterController.asStateFlow()

    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)

    private val isNetworkAvailable = AtomicBoolean(false)

    private val fetchCheckMutex = Mutex()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            val isValidated =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
            isNetworkAvailable.set(isValidated)
            if (isValidated) {
                fetchUpdates(manualRefresh = false)
            }
        }

        override fun onLost(network: Network) {
            isNetworkAvailable.set(false)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val controller = (service as UpdaterService.LocalBinder).service.updaterController
            _updaterController.value = controller

            viewModelScope.launch {
                val cached = repository.getCachedUpdates()
                applyUpdates(cached, controller)
                refreshUpdateIds(controller)
                if (isNetworkAvailable.get()) {
                    fetchUpdates(manualRefresh = false)
                }
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            _updaterController.value = null
            _uiState.update { it.copy(updateIds = emptyList()) }
        }
    }

    init {
        Intent(application, UpdaterService::class.java).also {
            application.startService(it)
            application.bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        connectivityManager?.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).build(),
            networkCallback,
        )
    }

    override fun onCleared() {
        super.onCleared()
        connectivityManager?.unregisterNetworkCallback(networkCallback)

        getApplication<Application>().unbindService(serviceConnection)
    }

    fun fetchUpdates(manualRefresh: Boolean = false) {
        val controller = _updaterController.value ?: return

        viewModelScope.launch {
            if (!fetchCheckMutex.tryLock()) {
                return@launch
            }

            try {
                val lastCheck = repository.getLastCheckTimestamp()
                val now = System.currentTimeMillis()
                if (now - lastCheck < MAX_REFRESH_INTERVAL) {
                    if (manualRefresh) {
                        _uiEvent.trySend(UiEvent.ShowMessage(R.string.snack_no_updates_found))
                    }
                    return@launch
                }

                _uiState.update { it.copy(isCheckingForUpdates = true) }
                val result = repository.fetchUpdates()
                _uiState.update { it.copy(isCheckingForUpdates = false) }

                when (result) {
                    is UpdateCheckRepository.FetchResult.Success -> {
                        val newUpdatesAdded = applyUpdates(result.updates, controller)
                        refreshUpdateIds(controller)
                        UpdatesCheckReceiver.updateRepeatingUpdatesCheck(getApplication())
                        UpdatesCheckReceiver.cancelUpdatesCheck(getApplication())

                        _uiState.update {
                            it.copy(lastCheckTimestamp = repository.getLastCheckTimestamp())
                        }

                        if (manualRefresh) {
                            val msgId = if (newUpdatesAdded) {
                                R.string.snack_updates_found
                            } else {
                                R.string.snack_no_updates_found
                            }
                            _uiEvent.trySend(UiEvent.ShowMessage(msgId))
                        }
                    }

                    is UpdateCheckRepository.FetchResult.NetworkError, is UpdateCheckRepository.FetchResult.ParseError -> {
                        _uiEvent.trySend(
                            UiEvent.ShowMessage(R.string.snack_updates_check_failed, long = true)
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isCheckingForUpdates = false) }
                fetchCheckMutex.unlock()
            }
        }
    }

    private fun refreshUpdateIds(controller: UpdaterController) {
        val sortedIds = controller.updates.sortedByDescending { it.timestamp }.map { it.downloadId }
        _uiState.update {
            it.copy(updateIds = sortedIds.toMutableList())
        }
    }

    private fun applyUpdates(
        updates: List<UpdateInfo>,
        controller: UpdaterController,
    ): Boolean {
        var newUpdates = false
        val updatesOnline = mutableListOf<String>()
        for (update in updates) {
            newUpdates = newUpdates or controller.addUpdate(update)
            updatesOnline.add(update.downloadId)
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true)
        return newUpdates
    }

    companion object {
        private const val MAX_REFRESH_INTERVAL = 60000L
    }
}
