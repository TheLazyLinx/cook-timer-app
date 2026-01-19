package ro.maris.tools.cooktimer.ui.sound

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ro.maris.tools.cooktimer.audio.SoundDetector
import javax.inject.Inject

@HiltViewModel
class SoundTestViewModel @Inject constructor(
    private val detector: SoundDetector
) : ViewModel() {
    val state = detector.state

    fun startDetection(): Boolean {
        return detector.start()
    }
}
