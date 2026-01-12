package ro.maris.tools.cooktimer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ro.maris.tools.cooktimer.domain.model.StartMode

@Entity(
    tableName = "steps",
    foreignKeys = [
        ForeignKey(
            entity = PresetEntity::class,
            parentColumns = ["id"],
            childColumns = ["preset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["preset_id"])]
)
data class StepEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "preset_id") val presetId: String,
    val position: Int,
    val title: String,
    val durationSec: Int?,
    val startMode: StartMode,
    val notes: String? = null
)
