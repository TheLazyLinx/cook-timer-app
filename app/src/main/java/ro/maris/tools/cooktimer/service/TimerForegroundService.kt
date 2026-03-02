package ro.maris.tools.cooktimer.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import ro.maris.tools.cooktimer.MainActivity
import ro.maris.tools.cooktimer.R
import ro.maris.tools.cooktimer.audio.SfxId
import ro.maris.tools.cooktimer.audio.SfxPlayer
import ro.maris.tools.cooktimer.domain.model.Phase
import javax.inject.Inject

/**
 * Foreground service that keeps the process alive while a timer is counting down.
 *
 * Communication is entirely via [Intent] extras — no shared-memory coupling with
 * ViewModels beyond [ActiveTimerManager] starting/stopping the service.
 *
 * The service maintains its own 1-second tick loop so it can:
 * 1. Update the persistent notification.
 * 2. Detect timer completion even if the UI is destroyed.
 */
@AndroidEntryPoint
class TimerForegroundService : Service() {

    companion object {
        const val ACTION_UPDATE = "ro.maris.tools.cooktimer.action.TIMER_UPDATE"
        const val ACTION_STOP = "ro.maris.tools.cooktimer.action.TIMER_STOP"

        const val EXTRA_LABEL = "label"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_ENDS_AT = "endsAt"
        const val EXTRA_PAUSED_REMAINING = "pausedRemaining"
        const val EXTRA_IS_STOPWATCH = "isStopwatch"
        const val EXTRA_SW_STARTED_AT = "swStartedAt"
        const val EXTRA_SW_ACCUMULATED = "swAccumulated"
        const val EXTRA_STEP_INDEX = "stepIndex"
        const val EXTRA_TOTAL_STEPS = "totalSteps"

        private const val ONGOING_NOTIFICATION_ID = 2001
        private const val FINISHED_NOTIFICATION_ID = 2002
    }

    @Inject lateinit var sfxPlayer: SfxPlayer

    // --- Timer state (updated via intents) ---
    private var label: String = ""
    private var phase: Phase = Phase.READY
    private var endsAtEpochSec: Long = 0L
    private var pausedRemainingSec: Int = 0
    private var isStopwatch: Boolean = false
    private var swStartedAtEpochSec: Long = 0L
    private var swAccumulatedSec: Int = 0
    private var stepIndex: Int = 0
    private var totalSteps: Int = 1
    private var finished: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (finished) return
            tick()
            handler.postDelayed(this, 1_000L)
        }
    }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> handleUpdate(intent)
            ACTION_STOP -> {
                tearDown()
                return START_NOT_STICKY
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Intent processing
    // ------------------------------------------------------------------

    private fun handleUpdate(intent: Intent) {
        label = intent.getStringExtra(EXTRA_LABEL) ?: label
        phase = runCatching { Phase.valueOf(intent.getStringExtra(EXTRA_PHASE)!!) }
            .getOrDefault(phase)
        endsAtEpochSec = intent.getLongExtra(EXTRA_ENDS_AT, endsAtEpochSec)
        pausedRemainingSec = intent.getIntExtra(EXTRA_PAUSED_REMAINING, pausedRemainingSec)
        isStopwatch = intent.getBooleanExtra(EXTRA_IS_STOPWATCH, isStopwatch)
        swStartedAtEpochSec = intent.getLongExtra(EXTRA_SW_STARTED_AT, swStartedAtEpochSec)
        swAccumulatedSec = intent.getIntExtra(EXTRA_SW_ACCUMULATED, swAccumulatedSec)
        stepIndex = intent.getIntExtra(EXTRA_STEP_INDEX, stepIndex)
        totalSteps = intent.getIntExtra(EXTRA_TOTAL_STEPS, totalSteps)
        finished = false

        startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification())

        handler.removeCallbacks(tickRunnable)
        if (phase == Phase.RUNNING || isStopwatch) {
            handler.postDelayed(tickRunnable, 1_000L)
        }
    }

    // ------------------------------------------------------------------
    // Tick loop
    // ------------------------------------------------------------------

    private fun tick() {
        if (phase == Phase.RUNNING && !isStopwatch && endsAtEpochSec > 0) {
            val remaining = (endsAtEpochSec - nowEpochSec()).toInt()
            if (remaining <= 0) {
                onTimerFinished()
                return
            }
        }
        updateOngoingNotification()
    }

    private fun onTimerFinished() {
        finished = true
        handler.removeCallbacks(tickRunnable)

        // Play the completion sound (even with screen off — see SfxPlayer fix)
        sfxPlayer.play(SfxId.PresetFinished)

        // Post a high-priority "finished" notification
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(FINISHED_NOTIFICATION_ID, buildFinishedNotification())

        // Remove the ongoing foreground notification and stop
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Notification builders
    // ------------------------------------------------------------------

    private fun buildOngoingNotification(): Notification {
        val text = when {
            phase == Phase.PAUSED && !isStopwatch ->
                "Paused \u2014 ${formatTime(pausedRemainingSec)}"
            phase == Phase.PAUSED && isStopwatch ->
                "Stopwatch paused \u2014 ${formatTime(swAccumulatedSec)}"
            isStopwatch -> {
                val elapsed = computeStopwatchElapsed()
                "Stopwatch \u2014 ${formatTime(elapsed)}"
            }
            else -> {
                val remaining = (endsAtEpochSec - nowEpochSec()).toInt().coerceAtLeast(0)
                "${formatTime(remaining)} remaining"
            }
        }

        val stepSuffix = if (totalSteps > 1) " (${stepIndex + 1}/$totalSteps)" else ""

        return NotificationCompat.Builder(this, NotificationChannels.TIMER_RUNNING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(label + stepSuffix)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(launcherPendingIntent())
            .build()
    }

    private fun buildFinishedNotification(): Notification {
        return NotificationCompat.Builder(this, NotificationChannels.TIMER_FINISHED)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(label)
            .setContentText("Timer finished!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(launcherPendingIntent())
            .build()
    }

    private fun updateOngoingNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun launcherPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun computeStopwatchElapsed(): Int {
        val live = if (swStartedAtEpochSec > 0) {
            (nowEpochSec() - swStartedAtEpochSec).toInt().coerceAtLeast(0)
        } else 0
        return swAccumulatedSec + live
    }

    private fun nowEpochSec(): Long = System.currentTimeMillis() / 1000L

    private fun formatTime(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun tearDown() {
        handler.removeCallbacks(tickRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
