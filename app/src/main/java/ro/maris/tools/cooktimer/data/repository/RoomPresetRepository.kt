package ro.maris.tools.cooktimer.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ro.maris.tools.cooktimer.data.local.dao.PresetDao
import ro.maris.tools.cooktimer.data.local.dao.StepDao
import ro.maris.tools.cooktimer.data.local.db.AppDatabase
import ro.maris.tools.cooktimer.data.local.entity.PresetEntity
import ro.maris.tools.cooktimer.data.local.entity.PresetWithSteps
import ro.maris.tools.cooktimer.data.local.entity.StepEntity
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.domain.model.StartMode
import ro.maris.tools.cooktimer.domain.model.Step
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPresetRepository @Inject constructor(
    private val database: AppDatabase,
    private val presetDao: PresetDao,
    private val stepDao: StepDao,
    private val timeProvider: TimeProvider
) : PresetRepository {

    override fun observePresets(): Flow<List<Preset>> {
        return presetDao.observePresetsWithSteps()
            .map { presets -> presets.map { it.toDomain() } }
    }

    override fun observePreset(presetId: String): Flow<Preset?> {
        return presetDao.observePresetWithSteps(presetId)
            .map { preset -> preset?.toDomain() }
    }

    override suspend fun savePreset(preset: Preset) {
        val nowEpochSec = timeProvider.nowEpochSec()
        val presetEntity = preset.toEntity(nowEpochSec)
        val steps = preset.toStepEntities()
        database.withTransaction {
            presetDao.upsertPreset(presetEntity)
            stepDao.deleteStepsForPreset(preset.id)
            if (steps.isNotEmpty()) {
                stepDao.upsertSteps(steps)
            }
        }
    }

    override suspend fun seedBuiltInsIfEmpty() {
        val nowEpochSec = timeProvider.nowEpochSec()
        val builtIns = builtInPresets()
        database.withTransaction {
            builtIns.forEach { preset ->
                presetDao.upsertPreset(preset.toEntity(nowEpochSec))
                stepDao.deleteStepsForPreset(preset.id)
                stepDao.upsertSteps(preset.toStepEntities())
            }
        }
    }

    override suspend fun deletePreset(presetId: String) {
        database.withTransaction {
            stepDao.deleteStepsForPreset(presetId)
            presetDao.deletePresetById(presetId)
        }
    }

    private fun PresetWithSteps.toDomain(): Preset {
        val orderedSteps = steps
            .sortedBy { it.position }
            .map { it.toDomain() }
        return Preset(
            id = preset.id,
            name = preset.name,
            steps = orderedSteps,
            isBuiltIn = preset.isBuiltIn
        )
    }

    private fun StepEntity.toDomain(): Step {
        return Step(
            id = id,
            title = title,
            durationSec = durationSec,
            startMode = startMode,
            notes = notes
        )
    }

    private fun Preset.toEntity(nowEpochSec: Long): PresetEntity {
        return PresetEntity(
            id = id,
            name = name,
            isBuiltIn = isBuiltIn,
            createdAtEpochSec = nowEpochSec,
            updatedAtEpochSec = nowEpochSec
        )
    }

    private fun Preset.toStepEntities(): List<StepEntity> {
        return steps.mapIndexed { index, step ->
            StepEntity(
                id = step.id,
                presetId = id,
                position = index,
                title = step.title,
                durationSec = step.durationSec,
                startMode = step.startMode,
                notes = step.notes
            )
        }
    }

    private fun builtInPresets(): List<Preset> {
        return listOf(
            Preset(
                id = "preset-boil",
                name = "Boil",
                isBuiltIn = true,
                steps = listOf(
                    Step(
                        id = "boil-wait",
                        title = "Wait for boil",
                        durationSec = null,
                        startMode = StartMode.MANUAL,
                        notes = "Start when water begins boiling"
                    ),
                    Step(
                        id = "boil-cook",
                        title = "Boil",
                        durationSec = 300,
                        startMode = StartMode.AUTO,
                        notes = "Keep rolling boil"
                    ),
                    Step(
                        id = "boil-rest",
                        title = "Rest",
                        durationSec = 60,
                        startMode = StartMode.AUTO,
                        notes = "Take off heat and wait"
                    )
                )
            ),
            Preset(
                id = "preset-bake",
                name = "Bake",
                isBuiltIn = true,
                steps = listOf(
                    Step(
                        id = "bake-preheat",
                        title = "Preheat",
                        durationSec = 600,
                        startMode = StartMode.AUTO,
                        notes = "Preheat oven"
                    ),
                    Step(
                        id = "bake-cook",
                        title = "Bake",
                        durationSec = 1800,
                        startMode = StartMode.AUTO,
                        notes = "Start baking"
                    ),
                    Step(
                        id = "bake-rest",
                        title = "Rest",
                        durationSec = 300,
                        startMode = StartMode.AUTO,
                        notes = "Cool before serving"
                    )
                )
            ),
            Preset(
                id = "preset-pan-fry",
                name = "Pan Fry",
                isBuiltIn = true,
                steps = listOf(
                    Step(
                        id = "pan-fry-heat",
                        title = "Heat pan",
                        durationSec = 180,
                        startMode = StartMode.AUTO,
                        notes = "Heat pan with oil"
                    ),
                    Step(
                        id = "pan-fry-cook",
                        title = "Fry",
                        durationSec = 600,
                        startMode = StartMode.AUTO,
                        notes = "Turn halfway through"
                    ),
                    Step(
                        id = "pan-fry-rest",
                        title = "Rest",
                        durationSec = 120,
                        startMode = StartMode.AUTO,
                        notes = "Let rest before serving"
                    )
                )
            ),
            Preset(
                id = "preset-popcorn",
                name = "Popcorn",
                isBuiltIn = true,
                steps = listOf(
                    Step(
                        id = "popcorn-heat",
                        title = "Heat oil",
                        durationSec = 120,
                        startMode = StartMode.AUTO,
                        notes = "Heat oil and add kernels"
                    ),
                    Step(
                        id = "popcorn-pop",
                        title = "Pop",
                        durationSec = 240,
                        startMode = StartMode.AUTO,
                        notes = "Listen for popping to slow"
                    ),
                    Step(
                        id = "popcorn-rest",
                        title = "Rest",
                        durationSec = 30,
                        startMode = StartMode.AUTO,
                        notes = "Let steam escape"
                    )
                )
            )
        )
    }
}
