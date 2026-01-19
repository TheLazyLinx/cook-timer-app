package ro.maris.tools.cooktimer.audio

import androidx.annotation.RawRes
import ro.maris.tools.cooktimer.R

enum class SfxId(@RawRes val resId: Int) {
    ButtonPress(R.raw.sfx_button_press),
    OpenScreen(R.raw.sfx_open_screen),
    PresetSaved(R.raw.sfx_save_preset),
    StepAdded(R.raw.sfx_add_step),
    TimerFinished(R.raw.sfx_timer_finished),
    StopwatchFinished(R.raw.sfx_stopwatch_finished),
    AutoParse(R.raw.sfx_auto_parse),
    PresetDuplicate(R.raw.sfx_preset_duplicate),
    PresetDelete(R.raw.sfx_preset_delete),
    PresetEdit(R.raw.sfx_preset_edit),
    PresetDuplicateClick(R.raw.sfx_preset_duplicate_click),
    NavPresets(R.raw.sfx_nav_presets),
    RunPrimaryNext(R.raw.sfx_run_primary_next),
    RunNavRow(R.raw.sfx_run_nav_row),
    PresetFinished(R.raw.sfx_preset_finished),
    StepStart(R.raw.sfx_step_start)
}
