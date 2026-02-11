/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.os.Bundle
import android.text.format.DateFormat
import androidx.preference.ListPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.Utils
import java.util.Date

class PreferencesFragment : SettingsBasePreferenceFragment() {

    private var periodicCheckPreference: SwitchPreferenceCompat? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        setupPeriodicCheckSwitch()
        setupPeriodicCheckInterval()
        setupStreamingMode()
        setupAutoDelete()
        setupABPerfMode()
        setupUpdateRecovery()
    }

    override fun onResume() {
        super.onResume()
        updateNextCheckSummary()
    }

    private fun setupPeriodicCheckSwitch() {
        periodicCheckPreference =
            findPreference<SwitchPreferenceCompat>(Constants.PREF_PERIODIC_CHECK_ENABLED)?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue as Boolean) {
                        UpdatesCheckReceiver.updateRepeatingUpdatesCheck(requireContext())
                    } else {
                        UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(requireContext())
                    }
                    view?.post { updateNextCheckSummary() }
                    true
                }
            }
        updateNextCheckSummary()
    }

    private fun setupPeriodicCheckInterval() {
        findPreference<ListPreference>(Constants.CheckInterval.PREF_KEY)?.apply {
            val currentInterval = Constants.CheckInterval.fromValue(context, value)
            summary = entries[currentInterval.ordinal]

            setOnPreferenceChangeListener { _, newValue ->
                val selectedInterval = Constants.CheckInterval.fromValue(context, newValue as String)
                summary = entries[selectedInterval.ordinal]
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(context)
                updateNextCheckSummary()
                true
            }
        }
    }

    private fun updateNextCheckSummary() {
        val context = context ?: return
        val sharedPreferences =
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val nextCheckTime = sharedPreferences.getLong(Constants.PREF_NEXT_UPDATE_CHECK, -1)
        periodicCheckPreference?.summary = if (nextCheckTime > 0) {
            val dateFormat = DateFormat.getDateFormat(context)
            val timeFormat = DateFormat.getTimeFormat(context)
            val date = Date(nextCheckTime)
            getString(
                R.string.menu_next_check_time,
                dateFormat.format(date),
                timeFormat.format(date)
            )
        } else {
            getString(R.string.menu_auto_updates_check_summary)
        }
    }

    private fun setupAutoDelete() {
        findPreference<SwitchPreferenceCompat>(Constants.PREF_AUTO_DELETE_UPDATES)?.apply {
            isVisible = !DeviceInfoUtils.isABDevice
        }
    }

    private fun setupStreamingMode() {
        findPreference<SwitchPreferenceCompat>(Constants.PREF_AB_STREAMING_MODE)?.apply {
            isVisible = DeviceInfoUtils.isABDevice
        }
    }

    private fun setupABPerfMode() {
        findPreference<SwitchPreferenceCompat>(Constants.PREF_AB_PERF_MODE)?.apply {
            isVisible = DeviceInfoUtils.isABDevice
            setOnPreferenceChangeListener { _, newValue ->
                Thread {
                    try {
                        val updateEngine = android.os.UpdateEngine()
                        updateEngine.setPerformanceMode(newValue as Boolean)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
                true
            }
        }
    }

    private fun setupUpdateRecovery() {
        findPreference<SwitchPreferenceCompat>(Constants.PREF_UPDATE_RECOVERY)?.apply {
            if (Utils.isRecoveryUpdateExecPresent()) {
                preferenceDataStore = object : PreferenceDataStore() {
                    override fun putBoolean(key: String, value: Boolean) {
                        DeviceInfoUtils.isRecoveryUpdateEnabled = value
                    }

                    override fun getBoolean(key: String, defValue: Boolean): Boolean =
                        DeviceInfoUtils.isRecoveryUpdateEnabled
                }
            } else {
                isVisible = false
            }
        }
    }
}
