package ro.maris.tools.cooktimer.domain.model

data class AppSettings(
    val soundEffectsEnabled: Boolean = true,
    val soundDetectionEnabled: Boolean = false,
    val soundTriggerAction: SoundTriggerAction = SoundTriggerAction.TOGGLE_PLAY_PAUSE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)
