/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.recyclerview.widget.RecyclerView
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.misc.StringGenerator
import org.lineageos.updater.model.UpdateStatus
import java.text.DateFormat

class UpdatesFragment : SettingsBasePreferenceFragment() {

    private var updaterController: UpdaterController? = null
    private var actionHandler: UpdateActionHandler? = null
    private var expandedId: String? = null
    private var showAll = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onCreateRecyclerView(
        inflater: android.view.LayoutInflater,
        parent: android.view.ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        return super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    fun setUpdaterController(controller: UpdaterController?) {
        updaterController = controller
        actionHandler = if (controller != null) {
            UpdateActionHandler(requireActivity() as UpdatesListActivity, controller)
        } else {
            null
        }
        rebuildPreferences()
    }

    fun notifyUpdateChanged(downloadId: String) {
        val controller = updaterController ?: return
        val update = controller.getUpdate(downloadId) ?: return
        val context = context ?: return

        val pref = findPreference<Preference>("update_$downloadId") ?: return

        if (pref is UpdatePreference) {
            pref.refresh()
        } else {
            pref.title = StringGenerator.getDateLocalizedUTC(
                context, DateFormat.LONG, update.timestamp
            )
            pref.summary = context.getString(R.string.list_build_version, update.version)
            if (UpdateStatus.isActive(update.status, update.persistentStatus)) {
                rebuildPreferences()
            }
        }
    }

    fun removeUpdate(downloadId: String) {
        if (expandedId == downloadId) expandedId = null
        rebuildPreferences()
    }

    fun refreshAll() {
        rebuildPreferences()
    }

    private fun rebuildPreferences() {
        val screen = preferenceScreen ?: return
        val controller = updaterController ?: run {
            screen.removeAll()
            return
        }
        val context = context ?: return

        screen.removeAll()

        val sortedUpdates = controller.getUpdates().sortedByDescending { it.timestamp }

        val category = PreferenceCategory(context)
        category.order = -1
        category.title = if (sortedUpdates.isEmpty()) {
            context.getString(R.string.snack_no_updates_found)
        } else {
            context.getString(R.string.new_updates_found_title)
        }
        screen.addPreference(category)

        val visibleUpdates = if (showAll) sortedUpdates else sortedUpdates.take(1)

        for (update in visibleUpdates) {
            val id = update.downloadId
            val isActive = UpdateStatus.isActive(update.status, update.persistentStatus)
            val isExpanded = expandedId == id || isActive

            if (isExpanded) {
                val pref = UpdatePreference(context).apply {
                    key = "update_$id"
                    this.downloadId = id
                    this.updaterController = controller
                    this.actionHandler = this@UpdatesFragment.actionHandler
                    if (!isActive) {
                        this.onCollapseRequested = { toggleExpand(it) }
                    }
                }
                screen.addPreference(pref)
            } else {
                val pref = Preference(context).apply {
                    key = "update_$id"
                    title = StringGenerator.getDateLocalizedUTC(
                        context, DateFormat.LONG, update.timestamp
                    )
                    summary = context.getString(
                        R.string.list_build_version, update.version
                    )
                    widgetLayoutResource = R.layout.preference_expand_widget
                    icon = context.getDrawable(
                        if (update.availableOnline) R.drawable.ic_download
                        else R.drawable.ic_sd_card
                    )
                    setOnPreferenceClickListener {
                        toggleExpand(id)
                        true
                    }
                }
                screen.addPreference(pref)
            }
        }

        if (sortedUpdates.size > 1) {
            val seeAll = Preference(context).apply {
                key = "see_all"
                setIcon(
                    if (showAll) R.drawable.settingslib_expressive_icon_up
                    else R.drawable.settingslib_expressive_icon_chevron
                )
                title = if (showAll) {
                    context.getString(R.string.see_less)
                } else {
                    context.getString(R.string.see_all_updates, sortedUpdates.size)
                }
                setOnPreferenceClickListener {
                    toggleSeeAll()
                    true
                }
            }
            screen.addPreference(seeAll)
        }
    }

    private fun toggleExpand(downloadId: String) {
        val wasExpanded = expandedId == downloadId
        expandedId = if (wasExpanded) null else downloadId
        rebuildPreferences()
    }

    private fun toggleSeeAll() {
        showAll = !showAll
        rebuildPreferences()
    }
}
