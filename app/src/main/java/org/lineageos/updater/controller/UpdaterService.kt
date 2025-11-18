/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.controller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import org.lineageos.updater.R
import org.lineageos.updater.UpdaterReceiver
import org.lineageos.updater.UpdatesActivity
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.Constants.NOTIFICATION_CHANNEL_ONGOING
import org.lineageos.updater.misc.Constants.NOTIFICATION_CHANNEL_POST_INSTALL
import org.lineageos.updater.misc.Constants.NOTIFICATION_ID_ONGOING
import org.lineageos.updater.misc.Constants.NOTIFICATION_ID_POST_INSTALL
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.misc.formatETA
import org.lineageos.updater.misc.getDateLocalizedUTC
import org.lineageos.updater.model.Update
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat

/**
 * Service that handles the background operations of the updater.
 * Manages notifications and orchestrates the installation process.
 */
class UpdaterService : Service() {

    private val binder = LocalBinder()
    private var hasClients = false

    private lateinit var broadcastReceiver: BroadcastReceiver
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationStyle: NotificationCompat.BigTextStyle
    private lateinit var notificationContentIntent: PendingIntent

    lateinit var updaterController: UpdaterController
        private set

    private var installer: UpdateInstaller? = null

    override fun onCreate() {
        super.onCreate()

        updaterController = UpdaterController.getInstance(this)

        notificationManager = getSystemService(NotificationManager::class.java)
        val notificationChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ONGOING,
            getString(R.string.ongoing_channel_title),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(notificationChannel)

        val postInstallChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_POST_INSTALL,
            getString(R.string.installing_update_finished),
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(postInstallChannel)

        val notificationIntent = Intent(this, UpdatesActivity::class.java)
        notificationContentIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Initialize the builder
        rebuildNotificationBuilder()

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID)
                when (intent.action) {
                    UpdaterController.ACTION_UPDATE_STATUS -> {
                        val update = updaterController.getUpdate(downloadId) ?: return
                        setNotificationTitle(update)
                        val extras = Bundle()
                        extras.putString(UpdaterController.EXTRA_DOWNLOAD_ID, downloadId)
                        // Rebuild notification to clear actions
                        rebuildNotificationBuilder()
                        notificationBuilder.setExtras(extras)
                        handleUpdateStatusChange(update)
                    }

                    UpdaterController.ACTION_DOWNLOAD_PROGRESS -> {
                        val update = updaterController.getUpdate(downloadId) ?: return
                        handleDownloadProgressChange(update)
                    }

                    UpdaterController.ACTION_INSTALL_PROGRESS -> {
                        val update = updaterController.getUpdate(downloadId) ?: return
                        setNotificationTitle(update)
                        handleInstallProgress(update)
                    }

                    UpdaterController.ACTION_UPDATE_REMOVED -> {
                        val isLocalUpdate = Update.LOCAL_ID == downloadId
                        val extras = notificationBuilder.extras
                        if (!isLocalUpdate && downloadId != null &&
                            downloadId == extras.getString(UpdaterController.EXTRA_DOWNLOAD_ID)
                        ) {
                            notificationBuilder.setExtras(null)
                            val update = updaterController.getUpdate(downloadId)
                            if (update?.status != UpdateStatus.INSTALLED) {
                                notificationManager.cancel(NOTIFICATION_ID_ONGOING)
                            }
                        }
                    }
                }
            }
        }
        val intentFilter = IntentFilter()
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS)
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS)
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS)
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED)
        // Use Context.RECEIVER_NOT_EXPORTED for API 33+ compatibility
        registerReceiver(broadcastReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(broadcastReceiver)
        notificationManager.cancel(NOTIFICATION_ID_ONGOING)
        notificationManager.cancel(NOTIFICATION_ID_POST_INSTALL)
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): UpdaterService = this@UpdaterService
    }

    override fun onBind(intent: Intent): IBinder {
        hasClients = true
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        hasClients = false
        tryStopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Starting service")

        if (intent == null || intent.action == null) {
            if (ABUpdateInstaller.isInstallingUpdate(this)) {
                // The service is being restarted.
                installer = ABUpdateInstaller.getInstance(this, updaterController)
                installer?.reconnect()
            }
        } else {
            when (intent.action) {
                ACTION_POST_REBOOT_CLEANUP -> {
                    val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                    handlePostRebootCleanup(downloadId)
                    tryStopSelf()
                }

                ACTION_DOWNLOAD_CONTROL -> {
                    val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                    val action = intent.getIntExtra(EXTRA_DOWNLOAD_CONTROL, -1)
                    when (action) {
                        DOWNLOAD_RESUME -> {
                            if (downloadId != null) updaterController.resumeDownload(downloadId)
                        }

                        DOWNLOAD_PAUSE -> {
                            if (downloadId != null) updaterController.pauseDownload(downloadId)
                        }

                        else -> {
                            Log.e(TAG, "Unknown download action")
                        }
                    }
                }

                ACTION_INSTALL_UPDATE -> {
                    val downloadId =
                        intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return START_NOT_STICKY
                    val update = updaterController.getUpdate(downloadId) ?: return START_NOT_STICKY
                    if (update.persistentStatus != UpdateStatus.Persistent.VERIFIED) {
                        throw IllegalArgumentException("${update.downloadId} is not verified")
                    }
                    try {
                        installer = if (Utils.isABUpdate(update.file!!)) {
                            ABUpdateInstaller.getInstance(this, updaterController)
                        } else {
                            LegacyUpdateInstaller.getInstance(this, updaterController)
                        }
                        installer?.install(downloadId)
                    } catch (e: IOException) {
                        Log.e(TAG, "Could not install update", e)
                        updaterController.getActualUpdate(downloadId)
                            ?.status = UpdateStatus.INSTALLATION_FAILED
                        updaterController.notifyUpdateChange(downloadId)
                    }
                }

                ACTION_INSTALL_STOP -> {
                    when {
                        LegacyUpdateInstaller.isInstalling -> {
                            installer = LegacyUpdateInstaller.getInstance(this, updaterController)
                        }

                        ABUpdateInstaller.isInstallingUpdate(this) -> {
                            installer = ABUpdateInstaller.getInstance(this, updaterController)
                            installer?.reconnect()
                        }
                    }
                    installer?.cancel()
                }

                ACTION_INSTALL_SUSPEND -> {
                    if (ABUpdateInstaller.isInstallingUpdate(this)) {
                        installer = ABUpdateInstaller.getInstance(this, updaterController)
                        installer?.reconnect()
                        installer?.suspend()
                    }
                }

                ACTION_INSTALL_RESUME -> {
                    if (ABUpdateInstaller.isInstallingUpdateSuspended(this)) {
                        installer = ABUpdateInstaller.getInstance(this, updaterController)
                        installer?.reconnect()
                        installer?.resume()
                    }
                }
            }
        }
        return if (ABUpdateInstaller.isInstallingUpdate(this)) START_STICKY else START_NOT_STICKY
    }

    private fun tryStopSelf() {
        if (!hasClients && !updaterController.hasActiveDownloads() &&
            !updaterController.isInstallingUpdate()
        ) {
            Log.d(TAG, "Service no longer needed, stopping")
            stopSelf()
        }
    }

    private fun rebuildNotificationBuilder() {
        notificationStyle = NotificationCompat.BigTextStyle()
        notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_system_update)
            .setShowWhen(false)
            .setStyle(notificationStyle)
            .setContentIntent(notificationContentIntent)
    }

    private fun handleUpdateStatusChange(update: UpdateInfo) {
        // Rebuild builder to clear old actions instead of accessing mActions.clear()
        rebuildNotificationBuilder()

        when (update.status) {
            UpdateStatus.DELETED -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationBuilder.setOngoing(false)
                notificationManager.cancel(NOTIFICATION_ID_ONGOING)
                tryStopSelf()
            }

            UpdateStatus.STARTING -> {
                notificationBuilder.setProgress(0, 0, true)
                notificationStyle.setSummaryText(null)
                val text = getString(R.string.download_starting_notification)
                notificationStyle.bigText(text)
                notificationBuilder.setStyle(notificationStyle)
                notificationBuilder.setSmallIcon(android.R.drawable.stat_sys_download)
                notificationBuilder.setTicker(text)
                notificationBuilder.setOngoing(true)
                notificationBuilder.setAutoCancel(false)
                startForeground(
                    NOTIFICATION_ID_ONGOING, notificationBuilder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
            }

            UpdateStatus.DOWNLOADING -> {
                val text = getString(R.string.downloading_notification)
                notificationStyle.bigText(text)
                notificationBuilder.setStyle(notificationStyle)
                notificationBuilder.setSmallIcon(android.R.drawable.stat_sys_download)
                notificationBuilder.addAction(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.pause_button),
                    getPausePendingIntent(update.downloadId)
                )
                notificationBuilder.setTicker(text)
                notificationBuilder.setOngoing(true)
                notificationBuilder.setAutoCancel(false)
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
            }

            UpdateStatus.PAUSED -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationBuilder.setProgress(100, update.progress, false)
                val text = getString(R.string.download_paused_notification)
                notificationStyle.bigText(text)
                notificationBuilder.setStyle(notificationStyle)
                notificationBuilder.setSmallIcon(R.drawable.ic_pause)
                notificationBuilder.addAction(
                    android.R.drawable.ic_media_play,
                    getString(R.string.resume_button),
                    getResumePendingIntent(update.downloadId)
                )
                notificationBuilder.setTicker(text)
                notificationBuilder.setOngoing(false)
                notificationBuilder.setAutoCancel(false)
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
                tryStopSelf()
            }

            UpdateStatus.PAUSED_ERROR -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                val progress = update.progress
                notificationBuilder.setProgress(if (progress > 0) 100 else 0, progress, false)
                val text = getString(R.string.download_paused_error_notification)
                notificationStyle.bigText(text)
                notificationBuilder.setStyle(notificationStyle)
                notificationBuilder.setSmallIcon(android.R.drawable.stat_sys_warning)
                notificationBuilder.addAction(
                    android.R.drawable.ic_media_play,
                    getString(R.string.resume_button),
                    getResumePendingIntent(update.downloadId)
                )
                notificationBuilder.setTicker(text)
                notificationBuilder.setOngoing(false)
                notificationBuilder.setAutoCancel(false)
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
                tryStopSelf()
            }

            UpdateStatus.VERIFYING -> {
                notificationBuilder.setProgress(0, 0, true)
                notificationStyle.setSummaryText(null)
                notificationBuilder.setStyle(notificationStyle)
                notificationBuilder.setSmallIcon(R.drawable.ic_system_update)
                val text = getString(R.string.verifying_download_notification)
                notificationStyle.bigText(text)
                notificationBuilder.setTicker(text)
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
            }

            UpdateStatus.VERIFIED -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationBuilder.setStyle(null)
                notificationBuilder.setSmallIcon(R.drawable.ic_system_update)
                notificationBuilder.setProgress(0, 0, false)
                val text = getString(R.string.download_completed_notification)
                notificationBuilder.setContentText(text)
                notificationBuilder.setTicker(text)
                notificationBuilder.setOngoing(false)
                notificationBuilder.setAutoCancel(true)
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
                tryStopSelf()
            }

            UpdateStatus.VERIFICATION_FAILED -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationBuilder.setStyle(null)
                notificationBuilder.setSmallIcon(android.R.drawable.stat_sys_warning)
                notificationBuilder.setProgress(0, 0, false)
                val text = getString(R.string.verification_failed_notification)
                notificationBuilder.setContentText(text)
                notificationBuilder.setTicker(text)
                notificationBuilder.setOngoing(false)
                notificationBuilder.setAutoCancel(true)
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
                tryStopSelf()
            }

            UpdateStatus.INSTALLING -> {
                notificationBuilder.setStyle(notificationStyle)
                notificationBuilder.setSmallIcon(R.drawable.ic_system_update)
                notificationBuilder.setProgress(0, 0, false)
                notificationStyle.setSummaryText(null)
                val text = if (LegacyUpdateInstaller.isInstalling) {
                    getString(R.string.dialog_prepare_zip_message)
                } else {
                    getString(R.string.installing_update)
                }
                notificationStyle.bigText(text)
                if (ABUpdateInstaller.isInstallingUpdate(this)) {
                    notificationBuilder.addAction(
                        android.R.drawable.ic_media_pause,
                        getString(R.string.suspend_button),
                        getSuspendInstallationPendingIntent()
                    )
                }
                notificationBuilder.setTicker(text)
                notificationBuilder.setOngoing(true)
                notificationBuilder.setAutoCancel(false)
                startForeground(
                    NOTIFICATION_ID_ONGOING, notificationBuilder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
            }

            UpdateStatus.INSTALLED -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(NOTIFICATION_ID_ONGOING)

                val rebootNotificationBuilder = NotificationCompat.Builder(
                    this, NOTIFICATION_CHANNEL_POST_INSTALL
                )
                val text = getString(R.string.installing_update_finished)
                rebootNotificationBuilder.setSmallIcon(R.drawable.ic_system_update)
                    .setContentTitle(text)
                    .setContentText(getString(R.string.reboot_to_complete_update))
                    .addAction(
                        R.drawable.ic_system_update,
                        getString(R.string.reboot_now),
                        getRebootPendingIntent()
                    )
                    .setOngoing(true)
                    .setAutoCancel(false)
                notificationManager.notify(
                    NOTIFICATION_ID_POST_INSTALL,
                    rebootNotificationBuilder.build()
                )

                tryStopSelf()
            }

            UpdateStatus.INSTALLATION_FAILED -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationBuilder.setStyle(null)
                notificationBuilder.setSmallIcon(android.R.drawable.stat_sys_warning)
                notificationBuilder.setProgress(0, 0, false)
                val text = getString(R.string.installing_update_error)
                notificationBuilder.setContentText(text)
                notificationBuilder.setTicker(text)
                notificationBuilder.setOngoing(false)
                notificationBuilder.setAutoCancel(true)
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
                tryStopSelf()
            }

            UpdateStatus.INSTALLATION_CANCELLED -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                tryStopSelf()
            }

            UpdateStatus.INSTALLATION_SUSPENDED -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationBuilder.setProgress(100, update.progress, false)
                val text = getString(R.string.installation_suspended_notification)
                notificationStyle.bigText(text)
                notificationBuilder.setStyle(notificationStyle)
                notificationBuilder.setSmallIcon(R.drawable.ic_pause)
                notificationBuilder.addAction(
                    android.R.drawable.ic_media_play,
                    getString(R.string.resume_button),
                    getResumeInstallationPendingIntent()
                )
                notificationBuilder.setTicker(text)
                notificationBuilder.setOngoing(true)
                notificationBuilder.setAutoCancel(false)
                notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
                tryStopSelf()
            }

            else -> {
                // No-op for other statuses
            }
        }
    }

    private fun handleDownloadProgressChange(update: UpdateInfo) {
        val progress = update.progress
        notificationBuilder.setProgress(100, progress, false)

        val percent = NumberFormat.getPercentInstance().format(progress / 100f)
        notificationStyle.setSummaryText(percent)

        setNotificationTitle(update)

        val speed = Formatter.formatFileSize(this, update.speed)
        val eta = formatETA(this, update.eta * 1000)
        notificationStyle.bigText(getString(R.string.text_download_speed, eta, speed))

        notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
    }

    private fun handleInstallProgress(update: UpdateInfo) {
        setNotificationTitle(update)
        val progress = update.installProgress
        notificationBuilder.setProgress(100, progress, false)
        val percent = NumberFormat.getPercentInstance().format(progress / 100f)
        notificationStyle.setSummaryText(percent)
        val notAB = LegacyUpdateInstaller.isInstalling
        notificationStyle.bigText(
            if (notAB) {
                getString(R.string.dialog_prepare_zip_message)
            } else if (update.isFinalizing) {
                getString(R.string.finalizing_package)
            } else {
                getString(R.string.preparing_ota_first_boot)
            }
        )
        notificationManager.notify(NOTIFICATION_ID_ONGOING, notificationBuilder.build())
    }

    private fun setNotificationTitle(update: UpdateInfo) {
        val buildDate = getDateLocalizedUTC(
            this, DateFormat.MEDIUM, update.timestamp
        )
        val buildInfo = getString(
            R.string.list_build_version_date, update.version, buildDate
        )
        notificationStyle.setBigContentTitle(buildInfo)
        notificationBuilder.setContentTitle(buildInfo)
    }

    private fun getResumePendingIntent(downloadId: String): PendingIntent {
        val intent = Intent(this, UpdaterService::class.java)
        intent.action = ACTION_DOWNLOAD_CONTROL
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        intent.putExtra(EXTRA_DOWNLOAD_CONTROL, DOWNLOAD_RESUME)
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getPausePendingIntent(downloadId: String): PendingIntent {
        val intent = Intent(this, UpdaterService::class.java)
        intent.action = ACTION_DOWNLOAD_CONTROL
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        intent.putExtra(EXTRA_DOWNLOAD_CONTROL, DOWNLOAD_PAUSE)
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getRebootPendingIntent(): PendingIntent {
        val intent = Intent(this, UpdaterReceiver::class.java)
        intent.action = UpdaterReceiver.ACTION_INSTALL_REBOOT
        return PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getSuspendInstallationPendingIntent(): PendingIntent {
        val intent = Intent(this, UpdaterService::class.java)
        intent.action = ACTION_INSTALL_SUSPEND
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getResumeInstallationPendingIntent(): PendingIntent {
        val intent = Intent(this, UpdaterService::class.java)
        intent.action = ACTION_INSTALL_RESUME
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun handlePostRebootCleanup(downloadId: String?) {
        if (downloadId == null) {
            return
        }
        val pref = PreferenceManager.getDefaultSharedPreferences(this)
        val deleteUpdate = pref.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false)
        // Always delete local updates
        val isLocal = Update.LOCAL_ID == downloadId
        if (deleteUpdate || isLocal) {
            updaterController.deleteUpdate(downloadId)
        }
        if (Utils.isABDevice()) {
            Thread {
                val installer = ABUpdateInstaller.getInstance(this, updaterController)
                installer.cleanup()
            }.start()
        }
    }

    companion object {
        const val ACTION_DOWNLOAD_CONTROL = "action_download_control"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_DOWNLOAD_CONTROL = "extra_download_control"
        const val ACTION_INSTALL_UPDATE = "action_install_update"
        const val ACTION_INSTALL_STOP = "action_install_stop"

        const val ACTION_INSTALL_SUSPEND = "action_install_suspend"
        const val ACTION_INSTALL_RESUME = "action_install_resume"

        const val ACTION_POST_REBOOT_CLEANUP = "action_post_reboot_cleanup"

        const val DOWNLOAD_RESUME = 0
        const val DOWNLOAD_PAUSE = 1

        private const val TAG = "UpdaterService"
    }
}
