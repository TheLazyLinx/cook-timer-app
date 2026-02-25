package ro.maris.tools.cooktimer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ro.maris.tools.cooktimer.audio.SfxPlayer
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.data.billing.PlayBillingRepository
import ro.maris.tools.cooktimer.ui.haptics.VibrationHelper
import javax.inject.Inject

@HiltAndroidApp
class CookTimerApp : Application() {

    @Inject lateinit var sfxPlayer: SfxPlayer
    @Inject lateinit var soundDetector: SoundDetector
    @Inject lateinit var vibrationHelper: VibrationHelper
    @Inject lateinit var billingRepository: PlayBillingRepository

    // Note: ProcessLifecycleOwner never dispatches ON_DESTROY on Android — the
    // process is simply killed. Singleton resources (sfxPlayer, soundDetector,
    // vibrationHelper, billingRepository) live for the process lifetime and are
    // cleaned up automatically by process death.  No explicit release is needed.
}
