package ro.maris.tools.cooktimer.ui.soundwave

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ro.maris.tools.cooktimer.audio.SoundDetectionEvent
import ro.maris.tools.cooktimer.audio.SoundDetector
import javax.inject.Inject

@HiltViewModel
class SoundwaveViewModel @Inject constructor(
    private val soundDetector: SoundDetector
) : ViewModel() {
    
    val detectorState = soundDetector.state
    
    private val _selectedEvent = MutableStateFlow<SoundDetectionEvent?>(null)
    val selectedEvent: StateFlow<SoundDetectionEvent?> = _selectedEvent
    
    fun selectEvent(event: SoundDetectionEvent?) {
        _selectedEvent.value = event
    }

    fun clearHistory() {
        soundDetector.clearHistory()
    }
}
