package ro.maris.tools.cooktimer.audio

import androidx.annotation.RawRes
import ro.maris.tools.cooktimer.R

enum class SfxId(@RawRes val resId: Int, val volume: Float = 1f, val durationMs: Long = 300L) {
    ButtonPress(R.raw.impactgeneric_light_003, 1f, 150L),
    OpenScreen(R.raw.impactwood_light_002, 1f, 200L),
    PresetSaved(R.raw.impactgeneric_light_003, 1f, 150L),
    StepAdded(R.raw.impactwood_light_002, 1f, 200L),
    TimerFinished(R.raw.toggle_002, 0.6f, 400L),
    StopwatchFinished(R.raw.toggle_002, 0.6f, 400L),
    AutoParse(R.raw.impactgeneric_light_003, 1f, 150L),
    PresetDuplicate(R.raw.impactgeneric_light_003, 1f, 150L),
    PresetDelete(R.raw.impactgeneric_light_003, 1f, 150L),
    PresetEdit(R.raw.impactgeneric_light_003, 1f, 150L),
    PresetDuplicateClick(R.raw.impactgeneric_light_003, 1f, 150L),
    NavPresets(R.raw.impactwood_light_002, 1f, 200L),
    RunPrimaryNext(R.raw.impactgeneric_light_003, 1f, 150L),
    RunNavRow(R.raw.impactgeneric_light_003, 1f, 150L),
    PresetFinished(R.raw.toggle_002, 0.6f, 400L),
    StepStart(R.raw.metalclick, 1f, 150L),
    DetectionFailed(R.raw.rollover6, 0.5f, 600L),
    DetectionSuccess(R.raw.drop_003, 0.5f, 500L)
}
