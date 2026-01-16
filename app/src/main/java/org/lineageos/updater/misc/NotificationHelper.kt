/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.misc

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import org.lineageos.updater.R
import org.lineageos.updater.UpdaterReceiver
import org.lineageos.updater.UpdatesActivity
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import java.text.DateFormat
import java.text.NumberFormat

/**
 * Centralized notification management for the Updater app.
 */
class NotificationHelper private constructor(context: Context) {

    private val context = context.applicationContext
    private val notificationManager: NotificationManager =
        requireNotNull(context.getSystemService())

    init {
        createChannels()
    }

    /**
     * Notifies the user that new updates are available.
     */
    fun notifyNewUpdates() = notificationManager.notify(
        NOTIFICATION_ID_NEW_UPDATES,
        completed(R.string.new_updates_found_title).build()
    )

    /**
     * Notifies the user of a status change for an update.
     *
     * @param update The update whose status changed
     */
    fun notifyUpdateStatus(update: UpdateInfo) {
        val id = update.downloadId.hashCode()
        val builder = ongoing().setContentTitle(update.formattedTitle)

        when (update.status) {
            UpdateStatus.STARTING ->
                notificationManager.notify(
                    id, builder, R.string.download_starting_notification,
                    ongoing = true, indeterminate = true
                )

            UpdateStatus.DOWNLOADING -> {
                builder.addActions(update.downloadId, paused = false)
                notificationManager.notify(
                    id, builder, R.string.downloading_notification,
                    ongoing = true, progress = update.progress
                )
            }

            UpdateStatus.PAUSED -> {
                builder.addActions(update.downloadId, paused = true)
                notificationManager.notify(
                    id, builder, R.string.download_paused_notification,
                    progress = update.progress
                )
            }

            UpdateStatus.PAUSED_ERROR -> {
                builder.addActions(update.downloadId, paused = true)
                notificationManager.notify(
                    id, builder, R.string.download_paused_error_notification,
                    progress = update.progress,
                    maxProgress = if (update.progress > 0) MAX_PROGRESS else 0
                )
            }

            UpdateStatus.VERIFYING ->
                notificationManager.notify(
                    id, builder, R.string.verifying_download_notification,
                    ongoing = true, indeterminate = true
                )

            UpdateStatus.VERIFIED ->
                notificationManager.notify(
                    id, builder, R.string.download_completed_notification,
                    autoCancel = true
                )

            UpdateStatus.VERIFICATION_FAILED ->
                notificationManager.notify(
                    id, builder, R.string.verification_failed_notification,
                    autoCancel = true
                )

            UpdateStatus.INSTALLING ->
                notificationManager.notify(
                    id, builder, R.string.installing_update,
                    ongoing = true
                )

            UpdateStatus.INSTALLATION_FAILED ->
                notificationManager.notify(
                    id, builder, R.string.installing_update_error,
                    autoCancel = true
                )

            UpdateStatus.INSTALLATION_SUSPENDED ->
                notificationManager.notify(
                    id, builder, R.string.installation_suspended_notification,
                    ongoing = true, progress = update.progress
                )

            else -> {}
        }
    }

    /**
     * Notifies the user of download progress for an update.
     *
     * @param update The update being downloaded
     */
    fun notifyDownloadProgress(update: UpdateInfo) {
        val speed = Formatter.formatFileSize(context, update.speed)
        val eta = StringGenerator.formatETA(context, update.eta * 1000)

        notificationManager.notify(
            update.downloadId.hashCode(),
            ongoing().apply {
                setContentTitle(update.formattedTitle)
                setProgress(MAX_PROGRESS, update.progress, false)
                setSubText(update.progress.asPercentage())
                setContentText(context.getString(R.string.text_download_speed, eta, speed))
                addActions(update.downloadId, paused = false)
            }.build()
        )
    }

    /**
     * Notifies the user of installation progress for an update.
     *
     * @param update The update being installed
     */
    fun notifyInstallProgress(update: UpdateInfo) {
        notificationManager.notify(
            update.downloadId.hashCode(),
            ongoing().apply {
                setContentTitle(update.formattedTitle)
                setProgress(MAX_PROGRESS, update.installProgress, false)
                setSubText(update.installProgress.asPercentage())
                setContentText(
                    when {
                        update.finalizing -> context.getString(R.string.finalizing_package)
                        else -> context.getString(R.string.preparing_ota_first_boot)
                    }
                )
            }.build()
        )
    }

    /**
     * Notifies the user that an update installation failed.
     *
     * @param buildInfo Information about the failed build
     */
    fun notifyUpdateFailed(buildInfo: String) {
        notificationManager.notify(
            NOTIFICATION_ID_COMPLETED,
            completed(R.string.update_failed_notification, buildInfo).apply {
                style = Notification.BigTextStyle().bigText(buildInfo)
            }.build()
        )
    }

    /**
     * Notifies the user that a reboot is required to complete installation.
     */
    fun notifyRebootRequired() {
        notificationManager.notify(
            NOTIFICATION_ID_COMPLETED,
            base(NOTIFICATION_CHANNEL_COMPLETED).apply {
                setContentIntent(activityIntent())
                setContentTitle(context.getString(R.string.installing_update_finished))
                setContentText(context.getString(R.string.reboot_to_complete_update))
                addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(R.string.reboot_now),
                        rebootIntent()
                    ).build()
                )
                setOngoing(true)
                setAutoCancel(false)
            }.build()
        )
    }

    /**
     * Checks if an update failed on boot and notifies the user if so.
     */
    fun checkAndNotifyUpdateFailedOnBoot() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        if (prefs.getBoolean(Constants.PREF_INSTALL_AGAIN, false) ||
            prefs.getBoolean(Constants.PREF_INSTALL_NOTIFIED, false)
        ) return

        val currentTimestamp = BuildInfoUtils.getBuildDateTimestamp()
        val lastTimestamp = prefs.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, -1)

        if (currentTimestamp == lastTimestamp) {
            val buildDate = StringGenerator.getDateLocalizedUTC(
                context, DateFormat.MEDIUM,
                prefs.getLong(Constants.PREF_INSTALL_NEW_TIMESTAMP, 0)
            )
            val buildInfo = context.getString(
                R.string.list_build_version_date,
                BuildInfoUtils.getBuildVersion(), buildDate
            )
            prefs.edit { putBoolean(Constants.PREF_INSTALL_NOTIFIED, true) }
            notifyUpdateFailed(buildInfo)
        }
    }

    /**
     * Builds an initial notification for starting an export operation.
     *
     * @param fileName The name of the file being exported
     * @return The notification to display
     */
    fun buildExportStart(fileName: String): Notification =
        ongoing().apply {
            setContentTitle(context.getString(R.string.dialog_export_title))
            setContentText(fileName)
            setProgress(MAX_PROGRESS, 0, true)
        }.build()

    /**
     * Notifies the user of export progress.
     *
     * @param progress The current progress percentage
     * @param fileName The name of the file being exported
     */
    fun notifyExportProgress(progress: Int, fileName: String) {
        notificationManager.notify(
            NOTIFICATION_ID_EXPORT,
            ongoing().apply {
                setContentTitle(context.getString(R.string.dialog_export_title))
                setContentText(fileName)
                setProgress(MAX_PROGRESS, progress, false)
            }.build()
        )
    }

    /**
     * Notifies the user that an export operation has finished.
     *
     * @param fileName The name of the exported file, or null if export failed
     */
    fun notifyExportFinished(fileName: String?) {
        val titleResId = if (fileName != null) {
            R.string.notification_export_success
        } else {
            R.string.notification_export_fail
        }
        notificationManager.notify(
            NOTIFICATION_ID_EXPORT,
            completed(titleResId, fileName).setAutoCancel(true).build()
        )
    }

    /**
     * Builds an ongoing notification for an update.
     *
     * @param update The update to build a notification for
     * @return The notification to display
     */
    fun buildOngoing(update: UpdateInfo): Notification =
        ongoing().apply {
            setContentTitle(update.formattedTitle)
            setContentText(context.getString(R.string.downloading_notification))
            setProgress(MAX_PROGRESS, update.progress, false)
            setSubText(update.progress.asPercentage())
        }.build()

    /**
     * Cancels a notification.
     *
     * @param notificationId The ID of the notification to cancel
     */
    fun cancel(notificationId: Int) = notificationManager.cancel(notificationId)

    private fun base(channelId: String) = Notification.Builder(context, channelId).apply {
        setSmallIcon(R.drawable.ic_system_update)
        setOnlyAlertOnce(true)
        setVisibility(Notification.VISIBILITY_PUBLIC)
    }

    private fun ongoing() = base(NOTIFICATION_CHANNEL_ONGOING).apply {
        setContentIntent(activityIntent())
        setShowWhen(false)
    }

    private fun completed(titleResId: Int, contentText: String? = null) =
        base(NOTIFICATION_CHANNEL_COMPLETED).apply {
            setContentIntent(activityIntent())
            setContentTitle(context.getString(titleResId))
            setContentText(contentText)
            setAutoCancel(true)
        }

    private fun NotificationManager.notify(
        id: Int,
        builder: Notification.Builder,
        textResId: Int,
        ongoing: Boolean = false,
        autoCancel: Boolean = false,
        indeterminate: Boolean = false,
        progress: Int = 0,
        maxProgress: Int = MAX_PROGRESS,
    ) {
        val text = context.getString(textResId)
        notify(id, builder.apply {
            setContentText(text)
            setTicker(text)
            setOngoing(ongoing)
            setAutoCancel(autoCancel)
            // Hide progress bar when progress is 0 and not indeterminate
            val actualMax = if (progress == 0 && !indeterminate) 0 else maxProgress
            setProgress(actualMax, progress, indeterminate)
        }.build())
    }

    private fun Notification.Builder.addActions(
        downloadId: String,
        paused: Boolean,
    ) = apply {
        val actionResId = if (paused) R.string.action_resume else R.string.action_pause
        val control = if (paused) UpdaterService.DOWNLOAD_RESUME else UpdaterService.DOWNLOAD_PAUSE

        addAction(
            Notification.Action.Builder(
                null,
                context.getString(actionResId),
                controlIntent(downloadId, control)
            ).build()
        )
        addAction(
            Notification.Action.Builder(
                null,
                context.getString(R.string.action_cancel),
                controlIntent(downloadId, UpdaterService.DOWNLOAD_CANCEL)
            )
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_DELETE)
                .build()
        )
    }

    private fun controlIntent(downloadId: String, control: Int) = PendingIntent.getService(
        context,
        downloadId.hashCode() + control,
        Intent(context, UpdaterService::class.java).apply {
            action = UpdaterService.ACTION_DOWNLOAD_CONTROL
            putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId)
            putExtra(UpdaterService.EXTRA_DOWNLOAD_CONTROL, control)
        },
        PENDING_INTENT_FLAGS
    )

    private fun activityIntent() = PendingIntent.getActivity(
        context, 0,
        Intent(context, UpdatesActivity::class.java),
        PENDING_INTENT_FLAGS
    )

    private fun rebootIntent() = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, UpdaterReceiver::class.java).apply {
            action = UpdaterReceiver.ACTION_INSTALL_REBOOT
        },
        PENDING_INTENT_FLAGS
    )

    private fun createChannels() {
        notificationManager.createNotificationChannelGroup(
            NotificationChannelGroup(
                NOTIFICATION_CHANNEL_GROUP_ID,
                context.getString(R.string.display_name)
            )
        )

        listOf(
            Triple(
                NOTIFICATION_CHANNEL_ONGOING,
                R.string.ongoing_channel_title,
                NotificationManager.IMPORTANCE_LOW
            ),
            Triple(
                NOTIFICATION_CHANNEL_COMPLETED,
                R.string.completed_channel_title,
                NotificationManager.IMPORTANCE_HIGH
            ),
            Triple(
                NOTIFICATION_CHANNEL_NEW_UPDATES,
                R.string.new_updates_channel_title,
                NotificationManager.IMPORTANCE_LOW
            ),
        ).forEach { (id, nameRes, importance) ->
            notificationManager.createNotificationChannel(
                NotificationChannel(id, context.getString(nameRes), importance).apply {
                    group = NOTIFICATION_CHANNEL_GROUP_ID
                }
            )
        }
    }

    private fun Int.asPercentage() =
        NumberFormat.getPercentInstance().format(this / 100.0)

    private val UpdateInfo.formattedTitle: String
        get() = context.getString(R.string.list_build_version, version)

    companion object {

        const val NOTIFICATION_ID_NEW_UPDATES = 0
        const val NOTIFICATION_ID_COMPLETED = 1
        const val NOTIFICATION_ID_EXPORT = 2

        const val NOTIFICATION_CHANNEL_ONGOING = "notification_channel_ongoing"
        const val NOTIFICATION_CHANNEL_COMPLETED = "notification_channel_completed"
        const val NOTIFICATION_CHANNEL_NEW_UPDATES = "notification_channel_new_updates"
        const val NOTIFICATION_CHANNEL_GROUP_ID = "updater_notification_group"

        private const val MAX_PROGRESS = 100
        private const val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: NotificationHelper? = null

        @JvmStatic
        fun getInstance(context: Context) = instance ?: synchronized(this) {
            instance ?: NotificationHelper(context).also { instance = it }
        }
    }
}
