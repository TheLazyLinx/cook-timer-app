package ro.maris.tools.cooktimer.audio

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.PowerManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SfxPlayer @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
        ?: error("NotificationManager missing")
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
        ?: error("KeyguardManager missing")
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
        ?: error("PowerManager missing")

    private val soundPool = SoundPool.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setMaxStreams(4)
        .build()

    private val soundIds = SfxId.entries.associateWith { sfx ->
        soundPool.load(appContext, sfx.resId, 1)
    }

    var enabled: Boolean = true
    private var lastPlayAtMs: Long = 0L
    private val minIntervalMs: Long = 70L
    private val perSfxIntervalMs: Long = 140L
    private val lastPlayBySfx: MutableMap<SfxId, Long> = mutableMapOf()

    fun play(sfx: SfxId) {
        if (!enabled || !canPlayNow()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayAtMs < minIntervalMs) return
        val lastForSfx = lastPlayBySfx[sfx] ?: 0L
        if (now - lastForSfx < perSfxIntervalMs) return
        lastPlayAtMs = now
        lastPlayBySfx[sfx] = now
        val soundId = soundIds[sfx] ?: return
        soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    private fun canPlayNow(): Boolean {
        if (!powerManager.isInteractive) return false
        if (keyguardManager.isDeviceLocked || keyguardManager.isKeyguardLocked) return false
        if (notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            return false
        }
        return true
    }
}
