package ro.maris.tools.cooktimer.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class PresetWithSteps(
    @Embedded val preset: PresetEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "preset_id",
        entity = StepEntity::class
    )
    val steps: List<StepEntity>
)
