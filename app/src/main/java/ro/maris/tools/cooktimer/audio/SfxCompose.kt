package ro.maris.tools.cooktimer.audio

import androidx.compose.runtime.staticCompositionLocalOf

val LocalSfxPlayer = staticCompositionLocalOf<SfxPlayer> {
    error("SfxPlayer not provided")
}

fun sfxClick(
    sfx: SfxPlayer,
    id: SfxId = SfxId.ButtonPress,
    action: () -> Unit
): () -> Unit {
    return {
        sfx.play(id)
        action()
    }
}
