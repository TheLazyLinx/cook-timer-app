package ro.maris.tools.cooktimer.domain.model

data class AppSettings(
    val soundEffectsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val soundDetectionEnabled: Boolean = false,
    val soundTriggerAction: SoundTriggerAction = SoundTriggerAction.TOGGLE_PLAY_PAUSE,
    val allowedSoundProfiles: Set<String> = setOf("Snap", "Snap (Soft)", "Slap", "Knock", "Stomp"), // Default all enabled
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val segmentedDisplayEnabled: Boolean = true,
    val hasShownSoundHelpOverlay: Boolean = false
)
