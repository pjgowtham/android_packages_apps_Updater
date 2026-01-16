/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SegmentedButtonPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.misc.Constants
import java.text.DateFormat
import java.util.Date

class PreferencesFragment : SettingsBasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        setupPeriodicCheckSwitch()
        setupPeriodicCheckInterval()
        setupABPerfMode()
        setupUpdateRecovery()
    }

    private fun setupPeriodicCheckSwitch() {
        findPreference<SwitchPreferenceCompat>(Constants.CheckInterval.KEY_ENABLED)?.apply {
            summaryProvider = NextCheckSummaryProvider(requireContext())
            setOnPreferenceChangeListener { _, _ ->
                UpdatesCheckWorker.updateSchedule(requireContext())
                true
            }
        }
    }

    private fun setupPeriodicCheckInterval() {
        findPreference<SegmentedButtonPreference>(Constants.CheckInterval.KEY_INTERVAL)?.apply {
            setupButtons()
            setInitialSelection()
            setClickListener()
        }
    }

    private fun SegmentedButtonPreference.setupButtons() {
        Constants.CheckInterval.entries.forEachIndexed { index, interval ->
            val labelRes = when (interval) {
                Constants.CheckInterval.DAILY ->
                    R.string.menu_auto_updates_check_interval_daily

                Constants.CheckInterval.WEEKLY ->
                    R.string.menu_auto_updates_check_interval_weekly

                Constants.CheckInterval.MONTHLY ->
                    R.string.menu_auto_updates_check_interval_monthly
            }
            val iconRes = when (interval) {
                Constants.CheckInterval.DAILY -> R.drawable.ic_calendar_view_day
                Constants.CheckInterval.WEEKLY -> R.drawable.ic_calendar_view_week
                Constants.CheckInterval.MONTHLY -> R.drawable.ic_calendar_view_month
            }
            setUpButton(index, getString(labelRes), iconRes)
            setButtonVisibility(index, true)
        }
    }

    private fun SegmentedButtonPreference.setInitialSelection() {
        val defValue = resources.getInteger(R.integer.def_periodic_check_interval).toString()
        val current = sharedPreferences?.getString(key, null) ?: defValue
        val currentInterval = Constants.CheckInterval.fromValue(current)
        setCheckedIndex(currentInterval.ordinal)
    }

    private fun SegmentedButtonPreference.setClickListener() {
        setOnButtonClickListener { group, checkedId, isChecked ->
            if (!isChecked) return@setOnButtonClickListener

            val clickedIndex = (0 until group.childCount).find {
                group.getChildAt(it).id == checkedId
            } ?: return@setOnButtonClickListener

            val selectedInterval = Constants.CheckInterval.entries.getOrNull(clickedIndex)
                ?: return@setOnButtonClickListener

            if (callChangeListener(selectedInterval.value)) {
                sharedPreferences?.edit { putString(key, selectedInterval.value) }
                UpdatesCheckWorker.updateSchedule(context)
                view?.post {
                    findPreference<SwitchPreferenceCompat>(Constants.CheckInterval.KEY_ENABLED)?.let {
                        val sp = it.summaryProvider
                        it.summaryProvider = null
                        it.summaryProvider = sp
                    }
                }
            }
        }
    }

    private fun setupABPerfMode() {
        findPreference<SwitchPreferenceCompat>(Constants.AB_PERF_MODE)?.apply {
            isVisible = DeviceInfoUtils.isABDevice
        }
    }

    private fun setupUpdateRecovery() {
        findPreference<SwitchPreferenceCompat>(Constants.UPDATE_RECOVERY)?.apply {
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

    companion object {
        class NextCheckSummaryProvider(private val context: Context) :
            Preference.SummaryProvider<SwitchPreferenceCompat> {

            override fun provideSummary(preference: SwitchPreferenceCompat): CharSequence {
                if (!preference.isChecked) {
                    return context.getString(R.string.menu_auto_updates_check_summary)
                }

                val nextTime = Constants.CheckInterval.getScheduledTime(context)
                    ?: return context.getString(R.string.menu_auto_updates_check_summary)

                val dateFormat = DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT
                )
                return context.getString(
                    R.string.menu_auto_updates_next_check,
                    dateFormat.format(Date(nextTime))
                )
            }
        }
    }
}
