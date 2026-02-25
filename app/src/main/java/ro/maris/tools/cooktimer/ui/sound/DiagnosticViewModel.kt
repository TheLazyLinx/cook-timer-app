package ro.maris.tools.cooktimer.ui.sound

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.audio.DetectionDiagnostic
import ro.maris.tools.cooktimer.audio.SoundDetector
import javax.inject.Inject

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    private val detector: SoundDetector
) : ViewModel() {
    val state = detector.state
    private var lastHandledDetectionCount = 0

    private val tapRecognizer = SoundTapGestureRecognizer(viewModelScope) { tapCount ->
        tapCount.taps.forEachIndexed { index, event ->
            detector.annotateGestureForEvent(
                eventTimestampMs = event.timestampMs,
                groupId = tapCount.groupId,
                tapIndex = index + 1,
                tapCount = tapCount.tapCount,
                unpairedReason = tapCount.unpairedReason
            )
        }
    }

    init {
        viewModelScope.launch {
            detector.state.collectLatest { detectorState ->
                if (detectorState.count < lastHandledDetectionCount) {
                    lastHandledDetectionCount = detectorState.count
                }
                val newCount = (detectorState.count - lastHandledDetectionCount).coerceAtLeast(0)
                if (newCount > 0) {
                    detectorState.events
                        .take(newCount)
                        .asReversed()
                        .forEach { event ->
                            tapRecognizer.onTap(event)
                        }
                    lastHandledDetectionCount = detectorState.count
                }
            }
        }
    }

    /** Start detection from the diagnostic screen itself (standalone testing) */
    fun startDetection(): Boolean {
        detector.debugContext = "DIAGNOSTIC"
        tapRecognizer.clear()
        lastHandledDetectionCount = detector.state.value.count
        return detector.start("diagnostic")
    }

    fun stopDetection() {
        detector.stop("diagnostic")
        tapRecognizer.clear()
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
        tapRecognizer.clear()
        detector.stop("diagnostic")
    }
}
