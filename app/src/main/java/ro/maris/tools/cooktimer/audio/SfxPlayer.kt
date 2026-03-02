package ro.maris.tools.cooktimer.audio

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.PowerManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SfxPlayer @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var enabled: Boolean = true
        private set
    
    private var lastPlayAtMs: Long = 0L
    @Volatile
    private var lastSuccessfulPlaybackAtMs: Long = 0L

    // Active streams tracking
    private data class StreamInfo(
        val streamId: Int,
        val sfxId: SfxId,
        val startTimeMs: Long,
        val endTimeMs: Long,
        var currentVolume: Float
    )
    private val activeStreams = mutableListOf<StreamInfo>()
    private val streamsLock = Any()

    init {
        scope.launch {
            settingsRepository.observeSettings().collectLatest { settings ->
                enabled = settings.soundEffectsEnabled
            }
        }
    }

    fun play(sfx: SfxId) {
        if (!enabled) return
        if (!sfx.playWhenLocked && !canPlayNow()) return
        val now = SystemClock.elapsedRealtime()
        
        // Anti-spam global throttle (very short)
        if (now - lastPlayAtMs < 30L) return

        synchronized(streamsLock) {
            // 1. Cleanup expired streams
            activeStreams.removeAll { it.endTimeMs <= now }

            // 2. Handle same-sound cutoff (spamming logic)
            // If the same sound is already playing, stop it.
            val sameSoundStreams = activeStreams.filter { it.sfxId == sfx }
            sameSoundStreams.forEach { stream ->
                soundPool.stop(stream.streamId)
                activeStreams.remove(stream)
            }

            // 3. Handle different-sound overlap (ducking/fading)
            // If other sounds are active, fade them out to prioritize the new one.
            activeStreams.forEach { stream ->
                if (stream.sfxId != sfx) {
                    // Duck logic: reduce volume to 30% of original to avoid compounding
                    val newVol = stream.sfxId.volume * 0.3f
                    soundPool.setVolume(stream.streamId, newVol, newVol)
                    stream.currentVolume = newVol
                }
            }

            // Play
            lastPlayAtMs = now
            val soundId = soundIds[sfx] ?: return
            val volume = sfx.volume

            val streamId = soundPool.play(soundId, volume, volume, 1, 0, 1f)

            if (streamId != 0) {
                lastSuccessfulPlaybackAtMs = now
                activeStreams.add(
                    StreamInfo(
                        streamId = streamId,
                        sfxId = sfx,
                        startTimeMs = now,
                        endTimeMs = now + sfx.durationMs,
                        currentVolume = volume
                    )
                )
            }
        }
    }

    fun lastPlaybackTimestampMs(): Long = lastSuccessfulPlaybackAtMs


    private fun canPlayNow(): Boolean {
        if (!powerManager.isInteractive) return false
        if (keyguardManager.isDeviceLocked || keyguardManager.isKeyguardLocked) return false
        val filter = notificationManager.currentInterruptionFilter
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            return false
        }
        return true
    }

    fun release() {
        scope.cancel()
        soundPool.release()
    }
}
