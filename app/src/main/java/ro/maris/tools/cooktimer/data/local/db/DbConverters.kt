package ro.maris.tools.cooktimer.data.local.db

import androidx.room.TypeConverter
import ro.maris.tools.cooktimer.domain.model.StartMode

class DbConverters {
    @TypeConverter
    fun fromStartMode(value: StartMode): String = value.name

    @TypeConverter
    fun toStartMode(value: String): StartMode = StartMode.valueOf(value)
}
