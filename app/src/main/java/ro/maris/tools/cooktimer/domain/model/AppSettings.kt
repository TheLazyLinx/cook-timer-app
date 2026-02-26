package ro.maris.tools.cooktimer.domain.model

data class AppSettings(
    val soundEffectsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val soundDetectionEnabled: Boolean = false,
    val doubleTapAction: SoundTriggerAction = SoundTriggerAction.TOGGLE_PLAY_PAUSE,
    val tripleTapAction: SoundTriggerAction = SoundTriggerAction.PARSE,
    val allowedSoundProfiles: Set<String> = setOf(
        "Snap (High)", "Snap (Mid)", "Snap (Low)",
        "Clap (Airy)", "Clap", "Clap (Wet)"
    ),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColorHex: String = "FFE57373",
    val segmentedDisplayEnabled: Boolean = true,
    val hasShownSoundHelpOverlay: Boolean = false,
    val isPremium: Boolean = false
)
