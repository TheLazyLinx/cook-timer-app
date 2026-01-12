package ro.maris.tools.cooktimer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isBuiltIn: Boolean,
    val createdAtEpochSec: Long = 0L,
    val updatedAtEpochSec: Long = 0L
)
