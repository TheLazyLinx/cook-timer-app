package ro.maris.tools.cooktimer

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import ro.maris.tools.cooktimer.audio.SfxPlayer
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.ui.haptics.VibrationHelper
import javax.inject.Inject

@HiltAndroidApp
class CookTimerApp : Application() {
    
    @Inject lateinit var sfxPlayer: SfxPlayer
    @Inject lateinit var soundDetector: SoundDetector
    @Inject lateinit var vibrationHelper: VibrationHelper

    override fun onCreate() {
        super.onCreate()
        
        // Observe app lifecycle to release resources when app is truly destroyed
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // Release all singleton resources when app process is being destroyed
                sfxPlayer.release()
                soundDetector.release()
                vibrationHelper.release()
            }
        })
    }
}
