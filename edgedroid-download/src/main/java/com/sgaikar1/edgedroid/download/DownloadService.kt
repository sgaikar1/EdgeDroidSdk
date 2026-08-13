package com.sgaikar1.edgedroid.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Foreground service that keeps the process alive while model downloads are active. The actual
 * transfer runs in the [DownloadManager] (same process); this service only hosts the lifecycle
 * (foreground notification with progress + cancel) and re-triggers a download after process
 * recreation via [DownloadManager.startFromService].
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val cfg = manager()?.config ?: DownloadConfig.DEFAULT
        createChannel(cfg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_MODEL_ID)?.let { id ->
                    scope.launch { manager()?.cancel(id) }
                }
                maybeStop()
            }
            else -> {
                val modelJson = intent?.getStringExtra(EXTRA_MODEL) ?: return START_NOT_STICKY
                val model = runCatching { Json.decodeFromString(Model.serializer(), modelJson) }.getOrNull()
                    ?: return START_NOT_STICKY
                val mgr = manager()
                if (mgr == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundFor(model.id, mgr.config)
                mgr.startFromService(model)
                observe(mgr)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not cancel an in-progress model download.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun manager(): DownloadManager? = DownloadManagerHolder.manager

    private fun createChannel(cfg: DownloadConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                cfg.notificationChannelId,
                cfg.notificationChannelName,
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundFor(modelId: String, cfg: DownloadConfig) {
        val notif = buildNotification(
            cfg,
            "${cfg.notificationTitle} ($modelId)",
            "Preparing…",
            indeterminate = true,
            modelId = modelId,
        )
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun observe(mgr: DownloadManager) {
        observeJob?.cancel()
        val nm = getSystemService(NotificationManager::class.java)
        observeJob = scope.launch {
            mgr.allStates.collect { states ->
                val cfg = mgr.config
                val downloading = states.entries.firstOrNull { it.value is ModelDownloadState.Downloading }
                if (downloading != null) {
                    val s = downloading.value as ModelDownloadState.Downloading
                    val progress = s.totalBytes?.let { total ->
                        if (total > 0) (s.bytesReceived * 100L / total).toInt() else null
                    }
                    nm.notify(
                        NOTIF_ID,
                        buildNotification(
                            cfg,
                            "${cfg.notificationTitle} (${downloading.key})",
                            "${s.bytesReceived} / ${s.totalBytes ?: "?"}",
                            indeterminate = progress == null,
                            modelId = downloading.key,
                            progress = progress,
                        ),
                    )
                } else {
                    val terminal = states.isNotEmpty() && states.values.all {
                        it is ModelDownloadState.Completed ||
                            it is ModelDownloadState.Failed ||
                            it is ModelDownloadState.Cancelled
                    }
                    if (terminal) {
                        nm.cancel(NOTIF_ID)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun maybeStop() {
        val mgr = manager() ?: return stopSelf()
        val states = mgr.allStates.value
        if (states.isNotEmpty() && states.values.all {
                it is ModelDownloadState.Completed ||
                    it is ModelDownloadState.Failed ||
                    it is ModelDownloadState.Cancelled
            }
        ) {
            stopSelf()
        }
    }

    private fun buildNotification(
        cfg: DownloadConfig,
        title: String,
        subtext: String,
        indeterminate: Boolean,
        modelId: String,
        progress: Int? = null,
    ): Notification {
        val cancelIntent = Intent(this, DownloadService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_MODEL_ID, modelId)
        val cancelPi = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, cfg.notificationChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subtext)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress ?: 0, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
            .build()
    }

    companion object {
        internal const val EXTRA_MODEL = "model"
        internal const val EXTRA_MODEL_ID = "modelId"
        internal const val ACTION_CANCEL = "com.sgaikar1.edgedroid.action.CANCEL_DOWNLOAD"
        private const val NOTIF_ID = 1001
    }
}
