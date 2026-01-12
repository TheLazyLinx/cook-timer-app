package ro.maris.tools.cooktimer.domain.repository

import kotlinx.coroutines.flow.Flow
import ro.maris.tools.cooktimer.domain.model.Preset

interface PresetRepository {
    fun observePresets(): Flow<List<Preset>>
    fun observePreset(presetId: String): Flow<Preset?>
    suspend fun savePreset(preset: Preset)
    suspend fun seedBuiltInsIfEmpty()
    suspend fun deletePreset(presetId: String)
}
