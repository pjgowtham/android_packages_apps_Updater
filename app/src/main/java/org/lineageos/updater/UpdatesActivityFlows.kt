/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.lineageos.updater.viewmodel.UpdateCheckViewModel

/**
 * Sets up lifecycle-aware flow collectors that bridge the ViewModel's
 * Kotlin flows to the Java Activity's UI methods.
 */
fun UpdatesActivity.observeViewModel() {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                mViewModel.updaterController.collect { controller ->
                    mAdapter.setUpdaterController(controller)
                }
            }
            launch {
                mViewModel.uiState.collect { state ->
                    mAdapter.setData(state.updateIds)
                    mAdapter.notifyDataSetChanged()
                    val empty = state.updateIds.isEmpty()
                    findViewById<View>(R.id.no_new_updates_view).visibility =
                        if (empty) View.VISIBLE else View.GONE
                    findViewById<View>(R.id.recycler_view).visibility =
                        if (empty) View.GONE else View.VISIBLE
                    if (state.isCheckingForUpdates) {
                        refreshAnimationStart()
                    } else {
                        refreshAnimationStop()
                    }
                    updateLastCheckedString(state.lastCheckTimestamp)
                }
            }
            launch {
                mViewModel.uiEvent.collect { event ->
                    when (event) {
                        is UpdateCheckViewModel.UiEvent.ShowMessage -> {
                            showSnackbar(
                                event.messageId,
                                if (event.long) Snackbar.LENGTH_LONG
                                else Snackbar.LENGTH_SHORT
                            )
                        }
                    }
                }
            }

        }
    }
}
