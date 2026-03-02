package ro.maris.tools.cooktimer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Creates the app's notification channels. Safe to call repeatedly —
 * the system ignores re-creation of existing channels.
 */
object NotificationChannels {

    const val TIMER_RUNNING = "timer_running"
    const val TIMER_FINISHED = "timer_finished"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val running = NotificationChannel(
            TIMER_RUNNING,
            "Active Timer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while a timer is counting down"
            setShowBadge(false)
        }

        val finished = NotificationChannel(
            TIMER_FINISHED,
            "Timer Finished",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alert when a cooking timer completes"
        }

        manager.createNotificationChannels(listOf(running, finished))
    }
}
