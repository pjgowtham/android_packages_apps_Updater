/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.android.settingslib.widget.LayoutPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

/**
 * Fragment that builds the preference portion of the updates screen using SettingsLib.
 *
 * Contains:
 * - Updates RecyclerView (LayoutPreference)
 * - Local Update (Preference in category)
 * - Preferences (Preference in category)
 *
 * SettingsLib handles spacing, backgrounds, and the segmented card look via
 * [SettingsPreferenceGroupAdapter] and [MarginItemDecoration].
 */
class UpdatesContentFragment : SettingsBasePreferenceFragment() {

    private var updatesListPreference: LayoutPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        updatesListPreference = LayoutPreference(context, R.layout.updates_list_content).apply {
            key = KEY_UPDATES_LIST
            isSelectable = false
        }
        screen.addPreference(updatesListPreference!!)

        val localCategory = PreferenceCategory(context).apply {
            key = KEY_CATEGORY_LOCAL
        }
        screen.addPreference(localCategory)

        localCategory.addPreference(Preference(context).apply {
            key = KEY_LOCAL_UPDATE
            icon = ContextCompat.getDrawable(context, R.drawable.ic_sim_card_download)
            title = getString(R.string.local_update_name)
            summary = getString(R.string.menu_local_update_summary)
        })

        val prefsCategory = PreferenceCategory(context).apply {
            key = KEY_CATEGORY_PREFERENCES
        }
        screen.addPreference(prefsCategory)

        prefsCategory.addPreference(Preference(context).apply {
            key = KEY_PREFERENCES
            icon = ContextCompat.getDrawable(context, R.drawable.ic_settings_accent)
            title = getString(R.string.menu_preferences)
            summary = getString(R.string.menu_preferences_summary)
        })

        preferenceScreen = screen
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as? UpdatesActivity ?: return

        findPreference<Preference>(KEY_LOCAL_UPDATE)?.setOnPreferenceClickListener {
            activity.openUpdateImportPicker()
            true
        }

        findPreference<Preference>(KEY_PREFERENCES)?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), PreferencesActivity::class.java))
            true
        }

        setupUpdatesListViews(activity)
    }

    private fun setupUpdatesListViews(activity: UpdatesActivity) {
        val layoutPref = updatesListPreference ?: return

        val recyclerView = layoutPref.findViewById<RecyclerView>(R.id.recycler_view) ?: return
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = activity.adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        val seeAllButton = layoutPref.findViewById<View>(R.id.see_all_button)
        if (seeAllButton != null) {
            activity.adapter.setOnVisibilityChangeListener {
                seeAllButton.visibility =
                    if (activity.adapter.hasMore()) View.VISIBLE else View.GONE
            }
            seeAllButton.setOnClickListener {
                activity.adapter.showAll()
                seeAllButton.visibility = View.GONE
            }
        }
    }

    companion object {
        private const val KEY_UPDATES_LIST = "updates_list"
        private const val KEY_CATEGORY_LOCAL = "category_local"
        private const val KEY_LOCAL_UPDATE = "local_update"
        private const val KEY_CATEGORY_PREFERENCES = "category_preferences"
        private const val KEY_PREFERENCES = "preferences"
    }
}
