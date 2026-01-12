package ro.maris.tools.cooktimer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ro.maris.tools.cooktimer.data.local.entity.StepEntity

@Dao
interface StepDao {
    @Query("SELECT * FROM steps WHERE preset_id = :presetId ORDER BY position")
    fun observeStepsForPreset(presetId: String): Flow<List<StepEntity>>

    @Query("SELECT * FROM steps WHERE preset_id = :presetId ORDER BY position")
    suspend fun getStepsForPreset(presetId: String): List<StepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(steps: List<StepEntity>)

    @Query("DELETE FROM steps WHERE preset_id = :presetId")
    suspend fun deleteStepsForPreset(presetId: String)

    @Query("DELETE FROM steps WHERE id = :stepId")
    suspend fun deleteStepById(stepId: String)
}
