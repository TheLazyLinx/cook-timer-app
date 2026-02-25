package ro.maris.tools.cooktimer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import ro.maris.tools.cooktimer.data.local.entity.PresetEntity
import ro.maris.tools.cooktimer.data.local.entity.PresetWithSteps

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY name")
    fun observePresets(): Flow<List<PresetEntity>>

    @Transaction
    @Query("SELECT * FROM presets ORDER BY name")
    fun observePresetsWithSteps(): Flow<List<PresetWithSteps>>

    @Query("SELECT * FROM presets WHERE id = :presetId")
    suspend fun getPresetById(presetId: String): PresetEntity?

    @Transaction
    @Query("SELECT * FROM presets WHERE id = :presetId")
    fun observePresetWithSteps(presetId: String): Flow<PresetWithSteps?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :presetId")
    suspend fun deletePresetById(presetId: String)

    @Query("SELECT COUNT(*) FROM presets WHERE isBuiltIn = 1")
    suspend fun countBuiltIns(): Int
}
