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
        detector.debugContext = "TESTING"
        return detector.start()
    }
    
    fun copyLogsToClipboard(context: android.content.Context) {
        val logs = state.value.logs.joinToString("\n")
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Sound Detection Logs", logs)
        clipboard.setPrimaryClip(clip)
    }

    fun clearLogs() {
        // We need to implement clear logs on detector side if we want to persist clearing
        // For now, we rely on clearHistory which clears events but we might want separate.
        // Let's add clearLogs() to SoundDetector if needed or just clear history.
        detector.clearHistory()
    }
}
