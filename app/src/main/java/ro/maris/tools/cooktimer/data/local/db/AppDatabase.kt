package ro.maris.tools.cooktimer.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import ro.maris.tools.cooktimer.data.local.dao.PresetDao
import ro.maris.tools.cooktimer.data.local.dao.StepDao
import ro.maris.tools.cooktimer.data.local.entity.PresetEntity
import ro.maris.tools.cooktimer.data.local.entity.StepEntity

@Database(
    entities = [PresetEntity::class, StepEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao
    abstract fun stepDao(): StepDao

    companion object {
        const val NAME = "cook_timer.db"

        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
