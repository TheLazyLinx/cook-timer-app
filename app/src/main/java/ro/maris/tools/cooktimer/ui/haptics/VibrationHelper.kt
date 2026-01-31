package ro.maris.tools.cooktimer.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

interface VibrationHelper {
    fun vibrateTick()
    fun vibrateConfirm()
    fun vibrateVirtualKey()
    fun release()
}

@Singleton
class AndroidVibrationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : VibrationHelper {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hapticsEnabledApp = true

    init {
        scope.launch {
            settingsRepository.observeSettings().collectLatest {
                hapticsEnabledApp = it.hapticsEnabled
            }
        }
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    @Suppress("DEPRECATION")
    private fun shouldVibrate(): Boolean {
        if (!hapticsEnabledApp) return false
        if (!vibrator.hasVibrator()) return false
        val hapticsEnabled = try {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1
            ) != 0
        } catch (e: Exception) {
            true
        }
        return hapticsEnabled
    }

    override fun vibrateTick() {
        if (!shouldVibrate()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun vibrateConfirm() {
        if (!shouldVibrate()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun vibrateVirtualKey() {
        if (!shouldVibrate()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun release() {
        scope.cancel()
    }
}
