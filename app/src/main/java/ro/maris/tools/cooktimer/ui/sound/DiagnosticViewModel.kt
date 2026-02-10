package ro.maris.tools.cooktimer.ui.sound

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ro.maris.tools.cooktimer.audio.DetectionDiagnostic
import ro.maris.tools.cooktimer.audio.SoundDetector
import javax.inject.Inject

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    private val detector: SoundDetector
) : ViewModel() {
    val state = detector.state

    /** Start detection from the diagnostic screen itself (standalone testing) */
    fun startDetection(): Boolean {
        detector.debugContext = "DIAGNOSTIC"
        return detector.start("diagnostic")
    }

    fun stopDetection() {
        detector.stop("diagnostic")
    }

    fun clearDiagnostics() {
        detector.clearDiagnostics()
    }

    fun clearAll() {
        detector.clearHistory()
    }

    fun getDiagnosticsCsv(): String {
        val diags = state.value.diagnostics
        return DetectionDiagnostic.csvHeader() + "\n" + diags.joinToString("\n") { it.toCsvLine() }
    }

    fun copyDiagnosticsToClipboard(context: Context) {
        val csv = getDiagnosticsCsv()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Sound Diagnostics CSV", csv)
        clipboard.setPrimaryClip(clip)
    }

    fun updateNote(id: Long, note: String) {
        detector.updateDiagnosticNote(id, note)
    }

    fun copyLogsToClipboard(context: Context) {
        val logs = state.value.logs.joinToString("\n")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Sound Detection Logs", logs)
        clipboard.setPrimaryClip(clip)
    }

    override fun onCleared() {
        super.onCleared()
        detector.stop("diagnostic")
    }
}
