package app.videotranslator.engine

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
 * Keeps a translation running while the user is in other apps or the screen is off: a foreground
 * service (type mediaProcessing; dataSync before Android 15) with a notification showing the
 * progress and a Cancel button, and a partial wake lock so the processor keeps working. The work
 * itself stays in [TranslationEngine]; this only keeps the process alive and in view.
 *
 * Nothing here uses the network. The notification shows the video's name and progress, never
 * any subtitle text.
 */
class TranslationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TranslationEngine.cancel()
            return START_NOT_STICKY
        }
        createChannels(this)
        val state = TranslationEngine.state.value
        try {
            val type = if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, progressNotification(state), type)
        } catch (e: Exception) {
            // Not allowed to run in the foreground now: the translation still runs while the app
            // is on screen, as before.
            Log.w(TAG, "could not start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoTranslator:translate")
                .apply {
                    setReferenceCounted(false)
                    acquire(MAX_RUN_MS)
                }
        }
        if (watcher == null) {
            watcher = scope.launch {
                TranslationEngine.state
                    .map { Snapshot(it.phase, it.stage, (it.progress * 100).toInt(), it.stopping, it.videoName) }
                    .distinctUntilChanged()
                    .collect { snap ->
                        val s = TranslationEngine.state.value
                        if (snap.phase == Phase.Working) {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, progressNotification(s))
                        } else {
                            finish(s)
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits mediaProcessing services to 6 hours a day: stop cleanly when told. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground time limit reached; stopping the translation")
        TranslationEngine.cancel()
        stopEverything()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(state: JobState) {
        val text = when (state.phase) {
            Phase.Done -> "Done: ${state.lines.size} lines. Tap to see them."
            Phase.Cancelled -> "Stopped."
            Phase.Failed -> state.error ?: "Something went wrong."
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

    private fun progressNotification(state: JobState): Notification {
        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
        val loading = state.stage == Stage.LoadingModel || state.stage == Stage.LoadingLanguageModel || state.stopping
        val text = when {
            state.stopping -> "Stopping…"
            state.stage == Stage.ReadingAudio -> "Reading the sound… $percent%"
            state.stage == Stage.LoadingModel -> "Loading Whisper…"
            state.stage == Stage.Whisper && state.languageModel != null -> "Writing down what is said… $percent%"
            state.stage == Stage.Whisper -> "Translating… $percent%"
            state.stage == Stage.LoadingLanguageModel -> "Loading ${state.languageModel}…"
            state.stage == Stage.LanguageModel -> "Translating into English… $percent%"
            else -> "Working…"
        }
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, TranslationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.videoName ?: "Translating a video")
            .setContentText(text)
            .setProgress(100, percent, loading)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private data class Snapshot(val phase: Phase, val stage: Stage?, val percent: Int, val stopping: Boolean, val name: String?)

    companion object {
        private const val TAG = "VTService"
        private const val NOTIFICATION_ID = 1
        private const val DONE_ID = 2
        private const val CHANNEL_PROGRESS = "translation"
        private const val CHANNEL_DONE = "translation-done"
        private const val ACTION_CANCEL = "app.videotranslator.CANCEL"
        private const val MAX_RUN_MS = 8L * 60 * 60 * 1000

        /** Starts the service for the translation that has just started. Safe to call twice. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TranslationService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start the background service", e)
            }
        }

        private fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Translating", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while a video is being translated"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a translation has finished"
                }
            )
        }
    }
}
