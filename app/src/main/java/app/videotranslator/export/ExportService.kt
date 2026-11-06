package app.videotranslator.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.videotranslator.MainActivity
import app.videotranslator.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a copy being saved going while the user is in other apps or the screen is off, as
 * TranslationService does for translations: a foreground service with the video's name, progress
 * and Cancel (never subtitle text), and a partial wake lock. The work stays in [ExportEngine].
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ExportEngine.cancel()
            return START_NOT_STICKY
        }
        createChannels(this)
        try {
            val type = if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, progressNotification(ExportEngine.state.value), type)
        } catch (e: Exception) {
            Log.w(TAG, "could not start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoTranslator:export")
                .apply {
                    setReferenceCounted(false)
                    acquire(MAX_RUN_MS)
                }
        }
        if (watcher == null) {
            watcher = scope.launch {
                ExportEngine.state
                    .map { Triple(it.phase, (it.progress * 100).toInt(), it.videoName) }
                    .distinctUntilChanged()
                    .collect { (phase, _, _) ->
                        val s = ExportEngine.state.value
                        if (phase == ExportPhase.Working || phase == ExportPhase.Saving) {
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progressNotification(s))
                        } else {
                            finish(s)
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground time limit reached; stopping the copy")
        ExportEngine.cancel()
        stopEverything()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(state: ExportState) {
        val text = when (state.phase) {
            ExportPhase.Done -> "Saved to the gallery, in Movies/${ExportEngine.FOLDER}."
            ExportPhase.Cancelled -> "Stopped. No copy was saved."
            ExportPhase.Failed -> state.error ?: "Something went wrong."
            else -> null
        }
        stopEverything()
        if (text != null) {
            val done = NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(state.videoName ?: "Video Translator")
                .setContentText(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(DONE_ID, done)
        }
    }

    private fun stopEverything() {
        watcher?.cancel()
        watcher = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun progressNotification(state: ExportState): Notification {
        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
        val saving = state.phase == ExportPhase.Saving
        val cancel = PendingIntent.getService(
            this, 2,
            Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.videoName ?: "Saving a copy")
            .setContentText(if (saving) "Putting the copy in the gallery…" else "Saving a copy with subtitles… $percent%")
            .setProgress(100, percent, saving)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
        if (!saving) builder.addAction(0, "Cancel", cancel)
        return builder.build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "VTExportService"
        private const val NOTIFICATION_ID = 3
        private const val DONE_ID = 4
        private const val CHANNEL_PROGRESS = "saving"
        private const val CHANNEL_DONE = "saving-done"
        private const val ACTION_CANCEL = "app.videotranslator.CANCEL_COPY"
        private const val MAX_RUN_MS = 6L * 60 * 60 * 1000

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start the background service", e)
            }
        }

        private fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Saving copies", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while a copy with subtitles is being saved"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Copy saved", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a copy with subtitles has been saved"
                }
            )
        }
    }
}
