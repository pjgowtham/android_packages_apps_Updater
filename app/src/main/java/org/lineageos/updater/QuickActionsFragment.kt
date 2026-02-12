/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class QuickActionsFragment : SettingsBasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.quick_actions, rootKey)

        findPreference<Preference>("local_updates")?.setOnPreferenceClickListener {
            (requireActivity() as UpdatesActivity).openLocalUpdatePicker()
            true
        }

        findPreference<Preference>("system_update_preferences")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), PreferencesActivity::class.java))
            true
        }
    }
}
